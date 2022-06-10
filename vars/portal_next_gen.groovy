#!groovy

def call(String appName) {
  String mvnSettingsFile = 'settings.xml'

  pipeline {
    options {
      disableConcurrentBuilds()
      buildDiscarder(logRotator(
        numToKeepStr: '10',
        daysToKeepStr: '30',
        artifactNumToKeepStr: '5',
        artifactDaysToKeepStr: '15'
      ))
    }

    agent {
      kubernetes {
        label "${appName}-${env.BRANCH_NAME}"
        defaultContainer 'maven'
        workspaceVolume dynamicPVC(requestsSize: '10Gi')
        yaml manifest()
      }
    }

    parameters {
      booleanParam(name: 'PUBLISH', defaultValue: false, description: 'Publishes docker image to registry')
    }

    environment {
      DOCKER_REGISTRY_ADDR = 'ghi-ghportal.jfrog.io'
      DOCKER_IMAGE = "${DOCKER_REGISTRY_ADDR}/${appName}"
      DOCKER_TAG = "${env.BRANCH_NAME}-${env.GIT_COMMIT.take(7)}-${env.BUILD_ID}"
      TARGET_ENVIRONMENT = 'dev'
      SEND_SLACK_NOTIFICATION = 'true'
    }

    stages {
      stage('Set build name') {
        steps {
          script {
            currentBuild.displayName = env.DOCKER_TAG
          }
        }
      }

      stage('Write maven settings to the workspace') {
        steps {
          script {
            writeFile(
              file: mvnSettingsFile,
              text: libraryResource('com/guardanthealth/portalnextgen/settings.xml')
            )
          }
        }
      }

      stage('Maven Sonar') {
        environment {
          ARTIFACTORY_USERNAME = '_gradle-publisher'
          ARTIFACTORY_PASSWORD = credentials('artifactory_password')
          SONARQUBE_LOGIN_TOKEN = credentials('sonar_auth_token')
        }
        steps {
          withMaven(mavenSettingsFilePath: mvnSettingsFile) {
            withSonarQubeEnv('sonar') {
              sh "mvn clean package"
              sh "mvn sonar:sonar -Dsonar.login=$SONARQUBE_LOGIN_TOKEN -Dsonar.branch.name=${utils.getCurrentBranch()}"
             }
          }
        }
      }
     stage('Maven package build') {
        environment {
          ARTIFACTORY_USERNAME = '_gradle-publisher'
          ARTIFACTORY_PASSWORD = credentials('artifactory_password')
        }
        steps {
          withMaven(mavenSettingsFilePath: mvnSettingsFile) {
              sh 'mvn clean package'
          }
        }
      }
     stage('Docker build') {
        steps {
         withCredentials([
            usernamePassword(
              credentialsId: 'artifactory_ghportal_credentials',
              usernameVariable: 'DOCKER_USER',
              passwordVariable: 'DOCKER_PASS',
            ),
          ]) {
            container('docker') {
              sh 'docker login $DOCKER_REGISTRY_ADDR -u $DOCKER_USER -p $DOCKER_PASS'
              sh "docker build -t ${env.DOCKER_IMAGE}:${env.DOCKER_TAG} -f ./deployment/docker/Dockerfile ."
            }
          }
        }
      }
      /*
      stage('Prisma image scan') {
        environment {
          PRISMA_RESULT_FILE = 'prisma-cloud-scan-results.json'
        }
        steps {
          container('jnlp') {
            prismaCloudScanImage(
              ca: '',
              cert: '',
              dockerAddress: 'unix:///var/run/docker.sock',
              image: "${env.DOCKER_IMAGE}:${env.DOCKER_TAG}",
              resultsFile: env.PRISMA_RESULT_FILE,
              project: '',
              ignoreImageBuildTime: true,
              key: '',
              logLevel: 'info',
              podmanPath: ''
            )
            prismaCloudPublish(resultsFilePattern: env.PRISMA_RESULT_FILE)
            archiveArtifacts(artifacts: env.PRISMA_RESULT_FILE, fingerprint: true)
          }
        }
      }
      */
      stage('Docker publish') {
        when {
          anyOf {
            branch 'master'
            expression {env.BRANCH_NAME.startsWith("release-")}
            expression { params.PUBLISH == true }
          }
        }
        steps {
         withCredentials([
            usernamePassword(
              credentialsId: 'artifactory_ghportal_credentials',
              usernameVariable: 'DOCKER_USER',
              passwordVariable: 'DOCKER_PASS',
            ),
          ]) {
            container('docker') {
              sh 'docker login $DOCKER_REGISTRY_ADDR -u $DOCKER_USER -p $DOCKER_PASS'
              sh "docker push ${env.DOCKER_IMAGE}:${env.DOCKER_TAG}"
            }
          }
        }
      }

      stage('Trigger deployment job') {
        when { branch 'master' }
        steps {
          build(
            job: "/deployments/argocd-update-image-tag",
            parameters: [
              string(name: 'APP_NAME', value: appName),
              string(name: 'ENVIRONMENT', value: env.TARGET_ENVIRONMENT),
              string(name: 'DOCKER_IMAGE_TAG', value: env.DOCKER_TAG)
            ], quietPeriod: 2
          )
        }
      }
    }

    post {
      success {
        script {
          slack.notify(
            repositoryName: appName,
            status: 'Success',
            additionalText: '',
            sendSlackNotification: env.SEND_SLACK_NOTIFICATION.toBoolean()
          )
        }
      }
      failure {
        script {
          slack.notify(
            repositoryName: appName,
            status: 'Failure',
            additionalText: '',
            sendSlackNotification: env.SEND_SLACK_NOTIFICATION.toBoolean()
          )
        }
      }
    }
  }
}

String manifest() {
  return '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins-agent.k8s.ghdna.io: portal-next-gen
spec:
  securityContext:
    runAsUser: 0
  containers:
  - name: maven
    image: maven:3-openjdk-11
    command:
    - cat
    tty: true
    resources:
      requests:
        memory: "1Gi"
        cpu: "500m"
      limits:
        memory: "2Gi"
        cpu: "1"
  - name: docker
    image: docker:18
    command:
    - cat
    tty: true
    resources:
      requests:
        memory: "512Mi"
        cpu: "100m"
      limits:
        memory: "512Mi"
        cpu: "500m"
    volumeMounts:
    - mountPath: /var/run/docker.sock
      name: socket
  - name: jnlp
    image: 'jenkins/inbound-agent:4.3-4-alpine'
    args:
    - \$(JENKINS_SECRET)
    - \$(JENKINS_NAME)
    volumeMounts:
    - mountPath: /var/run/docker.sock
      name: socket
  volumes:
  - name: socket
    hostPath:
      path: /var/run/docker.sock
'''
}
