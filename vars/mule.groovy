Map<String, String> businessGroupCodes = [
  "Business Apps": "BUS",
  "Enterprise Tech": "ENT",
]

def call(String mule_project, String build_tag) {
  def settings = libraryResource 'com/guardanthealth/settings.xml'
  String short_build_tag = utils.constrainLabelToSpecifications(build_tag)
  pipeline {
    agent {
      kubernetes {
        label "${short_build_tag}"
        defaultContainer 'common-binaries'
        yaml """
            apiVersion: v1
            kind: Pod
            metadata:
              labels:
                gh-jenkins-pod-type: docker
            spec:
              securityContext:
                runAsUser: 0
              containers:
              - name: maven
                securityContext:
                  runAsUser: 0
                image: maven:3.6.2-jdk-8-slim
                command:
                - "sh"
                - "-c"
                - "cat"
                tty: true
                volumeMounts:
                - mountPath: /shared
                  name: shared
              - name: docker
                image: docker:18
                command:
                - "sh"
                - "-c"
                - "apk update && apk add make && cat"
                tty: true
                volumeMounts:
                - mountPath: /var/run/docker.sock
                  name: socket
                - mountPath: /shared
                  name: shared
              - name: common-binaries
                image: ghi-ghinfra.jfrog.io/common-binaries:38f0265a
                command:
                - cat
                tty: true
                volumeMounts:
                - mountPath: /shared
                  name: shared
              volumes:
              - name: socket
                hostPath:
                  path: /var/run/docker.sock
              - name: shared
                hostPath:
                  path: /tmp
        """
      }
    }

    parameters {
      choice(
        name: "TARGET_ENVIRONMENT",
        description: "Destination environment",
        choices: ['dev', 'sqa', 'prod']
      )
      choice(
        name: "BUSINESS_GROUP",
        description: "Business group name",
        choices: ["Business Apps", "Enterprise Tech"]
      )
      booleanParam(
        name: 'SHOULD_DEPLOY',
        description: 'Deploy artifact to Anypoint when set to true',
        defaultValue: false
      )
    }

    environment {
      MULE_PROJECT                = "${mule_project}"
      REPO_NAME                   = "${mule_project}"
      SHARED_DIR                  = "/shared/${build_tag}/"
      GIT_COMMIT                  = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
      SEND_SLACK_NOTIFICATION     = true
      TARGET_ENVIRONMENT          = "${params.TARGET_ENVIRONMENT}"
      RELEASE_SUFFIX              = "${env.BRANCH_NAME}-${env.GIT_COMMIT.substring(0,8)}"
      SHOULD_DEPLOY               = "${params.SHOULD_DEPLOY}"
      BUSINESS_GROUP              = "${params.BUSINESS_GROUP}"
      ANYPOINT_CLIENT_SECRET_NAME = getAnypointClientSecretName(params.BUSINESS_GROUP, params.TARGET_ENVIRONMENT)
      ANYPOINT_KEY_SECRET_NAME    = getAnypointKeySecretName(params.BUSINESS_GROUP, params.TARGET_ENVIRONMENT)
      SPLUNK_TOKEN_SECRET_NAME    = getSplunkTokenSecretName(params.BUSINESS_GROUP, params.TARGET_ENVIRONMENT)
    }

    stages {
      stage('Create shared dir') {
        steps {
          sh "mkdir -p ${env.SHARED_DIR}"
        }
      }
      stage('Write maven settings to the workspace') {
        steps {
          script {
            writeFile(file: "settings.xml", text: settings)
          }
        }
      }
      stage('Release branch') {
        when {
          expression {env.BRANCH_NAME.split("release-").size() == 2}
        }
        steps {
          script{
            RELEASE_SUFFIX = env.BRANCH_NAME.split("release-")[1].trim()
          }
        }
      }
      stage('Non-release branch') {
        when {
          not {
            expression {env.BRANCH_NAME.split("release-").size() == 2}
          }
        }
        steps {
          script{
            RELEASE_SUFFIX = "-${env.RELEASE_SUFFIX}"
          }
        }
      }
      stage('Clean') {
        steps {
          container('maven') {
            withCredentials([
              usernamePassword(credentialsId: 'MULESOFT_NEXUS_REPOSITORY', usernameVariable: 'MULE_REPOSITORY_USERNAME', passwordVariable: 'MULE_REPOSITORY_PASSWORD'),
              usernamePassword(credentialsId: 'MULESOFT_DANDRUSZAK_DEBUG', usernameVariable: 'ANYPOINT_USERNAME', passwordVariable: 'ANYPOINT_PASSWORD')
            ]) {
              withMaven(mavenSettingsFilePath: 'settings.xml') {
                sh """
                  env
                  mvn -B clean
                """
              }
            }
          }
        }
      }
      stage('Run tests') {
        steps {
          container('maven') {
            withCredentials([
              usernamePassword(credentialsId: 'MULESOFT_NEXUS_REPOSITORY', usernameVariable: 'MULE_REPOSITORY_USERNAME', passwordVariable: 'MULE_REPOSITORY_PASSWORD'),
              usernamePassword(credentialsId: 'MULESOFT_DANDRUSZAK_DEBUG', usernameVariable: 'ANYPOINT_USERNAME', passwordVariable: 'ANYPOINT_PASSWORD')
            ]) {
              withMaven(mavenSettingsFilePath: 'settings.xml') {
                sh """
                  mvn -B test
                """
              }
            }
          }
        }
      }
      stage('Build and upload to Artifactory') {
        steps {
          container('maven') {
            withCredentials([
              usernamePassword(credentialsId: 'MULESOFT_NEXUS_REPOSITORY', usernameVariable: 'MULE_REPOSITORY_USERNAME', passwordVariable: 'MULE_REPOSITORY_PASSWORD'),
              usernamePassword(credentialsId: 'artifactory_svc_data_team', usernameVariable: 'ARTIFACTORY_USERNAME', passwordVariable: 'ARTIFACTORY_PASSWORD'),
            ]) {
              withEnv(["RELEASE_SUFFIX=${RELEASE_SUFFIX}"]) {
                withMaven(mavenSettingsFilePath: 'settings.xml') {
                  sh """
                    mvn -B package deploy -P${env.TARGET_ENVIRONMENT} -DskipTests
                  """
                }
              }
            }
          }
        }
      }
      stage('Publish to Anypoint') {
        when {
          expression { params.SHOULD_DEPLOY == true }
        }
        steps {
          container('maven') {
            withCredentials([
              usernamePassword(credentialsId: 'artifactory_svc_data_team', usernameVariable: 'ARTIFACTORY_USERNAME', passwordVariable: 'ARTIFACTORY_PASSWORD'),
              usernamePassword(credentialsId: "${env.ANYPOINT_CLIENT_SECRET_NAME}", usernameVariable: 'ANYPOINT_CLIENT_ID', passwordVariable: 'ANYPOINT_CLIENT_SECRET'),
              usernamePassword(credentialsId: 'MULESOFT_DANDRUSZAK_DEBUG', usernameVariable: 'ANYPOINT_USERNAME', passwordVariable: 'ANYPOINT_PASSWORD'),
              string(credentialsId: "${env.ANYPOINT_KEY_SECRET_NAME}", variable: 'MULESOFT_KEY'),
              string(credentialsId: "${env.SPLUNK_TOKEN_SECRET_NAME}", variable: 'SPLUNK_TOKEN')
            ]) {
              withEnv(["RELEASE_SUFFIX=${RELEASE_SUFFIX}"]) {
                withMaven(mavenSettingsFilePath: 'settings.xml') {
                  sh """
                     mvn -B dependency:copy -P${env.TARGET_ENVIRONMENT}
                     mvn -B mule:deploy -P${env.TARGET_ENVIRONMENT}
                  """
                }
              }
            }
          }
        }
      }
    }
    post {
      success {
        script {
          slack.notify(
            repositoryName: env.REPO_NAME,
            status: 'Success',
            additionalText: "",
            sendSlackNotification: env.SEND_SLACK_NOTIFICATION.toBoolean()
          )
        }
      }
      failure {
        script {
          slack.notify(
            repositoryName: env.REPO_NAME,
            status: 'Failure',
            additionalText: "",
            sendSlackNotification: env.SEND_SLACK_NOTIFICATION.toBoolean()
          )
        }
      }
    }
  }
}

String getAnypointClientSecretName(String businessGroup, String publishEnv) {
  String businessGroupCode = businessGroupCodes[businessGroup]
  String environment = publishEnv.toUpperCase()

  return "MULESOFT_ANYPOINT_CLIENT_${environment}_${businessGroupCode}"
}

String getAnypointKeySecretName(String businessGroup, String publishEnv) {
  String businessGroupCode = businessGroupCodes[businessGroup]
  String environment = publishEnv.toUpperCase()

  return "MULESOFT_ANYPOINT_KEY_${businessGroupCode}_${environment}"
}

String getSplunkTokenSecretName(String businessGroup, String publishEnv) {
  String businessGroupCode = businessGroupCodes[businessGroup]
  String environment

  if (publishEnv == "prod") {
    environment = "PROD"
  } else {
    environment = "NON-PROD"
  }

  return "MULESOFT_SPLUNK_TOKEN_${businessGroupCode}_${environment}"
}