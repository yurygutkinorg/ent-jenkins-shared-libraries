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
              - name: awscli
                image: ghinfra/awscli:2
                command:
                - cat
                tty: true
                volumeMounts:
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

    environment {
      MULE_PROJECT          = "${mule_project}"
      REPO_NAME               = "${mule_project}"
      SHARED_DIR              = "/shared/${build_tag}/"
      GIT_COMMIT              = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
      SEND_SLACK_NOTIFICATION = true
      TARGET_ENVIRONMENT      = "dev"
      RELEASE_NAME            = "${env.BRANCH_NAME}-${env.GIT_COMMIT.substring(0,8)}"
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
            RELEASE_NAME = env.BRANCH_NAME.split("release-")[1].trim()
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
            // Maven requires semver as a version. Commit SHA doesn't work here
            RELEASE_NAME = "1.0.0-${env.RELEASE_NAME}" // e.g. 1.0.0-master-b8cde1
          }
        }
      }
      stage('Build application and deploy') {
        steps {
          container('maven') {
            withCredentials([
                    string(credentialsId: 'artifactory_url', variable: 'ARTIFACTORY_URL'),
                    string(credentialsId: 'artifactory_username', variable: 'ARTIFACTORY_USERNAME'),
                    string(credentialsId: 'artifactory_password', variable: 'ARTIFACTORY_PASSWORD'),
                    usernamePassword(credentialsId: 'mule_nexus_repository', usernameVariable: 'MULE_REPOSITORY_USERNAME', passwordVariable: 'MULE_REPOSITORY_PASSWORD')
            ]) {
              withEnv(["RELEASE_NAME=${RELEASE_NAME}"]) {
                withMaven(mavenSettingsFilePath: 'settings.xml') {
                  sh """
                    mvn versions:set -DnewVersion=${env.RELEASE_NAME}
                    mvn -B \
                      -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=WARN \
                      -Dorg.slf4j.simpleLogger.showDateTime=true \
                      -Djava.awt.headless=true \
                      -DskipTests \
                      deploy \
                      -DaltDeploymentRepository=ghi-artifactory::default::${ARTIFACTORY_URL} \
                      -Demule-tools.version=1.0-beta33
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
