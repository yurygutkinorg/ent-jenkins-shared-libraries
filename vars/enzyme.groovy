
def call(String enzymeProject, String branchName, String buildTag) {
  String shortBuildTag = utils.constrainLabelToSpecifications(buildTag)
  String releaseVersion = getReleaseVersion(enzymeProject, branchName)

  pipeline {
    agent {
      kubernetes {
        label "${shortBuildTag}"
        defaultContainer 'common-binaries'
        yaml getManifest()
      }
    }

    environment {
      ENZYME_PROJECT          = "${enzymeProject}"
      DOCKER_TAG              = "${branchName}-${env.GIT_COMMIT}"
      RELEASE_VERSION         = "${releaseVersion}"
      TARGET_ENVIRONMENT      = "dev"
      SHARED_DIR              = "/shared/${buildTag}/"
      SEND_SLACK_NOTIFICATION = "true"
    }

    stages {
      stage('Create shared dir') {
        steps {
          sh "mkdir -p ${env.SHARED_DIR}"
        }
      }

      stage('Checkout gh-aws repo, move Makefile to workspace, and cleanup of gh-aws repo') {
        steps {
          dir('gh-aws') {
            git(
              url: 'https://github.com/guardant/gh-aws.git',
              credentialsId: 'ghauto-github',
              branch: "master"
            )
            echo 'Cloning Makefile to job workspace:'
            sh "cp ./deployment-scripts/enzyme/Makefile ${env.WORKSPACE}"
            echo 'Cleanup gh-aws project:'
            deleteDir()
          }
        }
      }

      stage('Metadata injection to config.properties') {
        environment {
          PROPERTIES_FILE_PATH = sh(script: 'find ./src/resources/config  -name config.properties', returnStdout: true).trim()
        }
        steps {
          script {
            if(PROPERTIES_FILE_PATH == "") {
              error("config.properties does not exist under /src/resources/config.")
            }
          }
          sh """
           cat <<EOF >> ${env.PROPERTIES_FILE_PATH}

# BUILD METADATA
${env.ENZYME_PROJECT}.git.sha=${env.GIT_COMMIT}
${env.ENZYME_PROJECT}.git.branch=${env.BRANCH_NAME}
${env.ENZYME_PROJECT}.build.id=${env.BUILD_ID}
${env.ENZYME_PROJECT}.build.number=${env.BUILD_NUMBER}
${env.ENZYME_PROJECT}.build.tag=${env.BUILD_TAG}
${env.ENZYME_PROJECT}.release.version=${env.RELEASE_VERSION}

EOF
          """
        }
      }

      stage('Build gradle, copy gradle binaries to shared directory, run unit test/static code analysis') {
        steps {
          withSonarQubeEnv('sonar') {
            withCredentials([
            string(credentialsId: 'artifactory_url', variable: 'ARTIFACTORY_URL'),
            string(credentialsId: 'artifactory_username', variable: 'ARTIFACTORY_USERNAME'),
            string(credentialsId: 'artifactory_password', variable: 'ARTIFACTORY_PASSWORD')
          ]) {
              container('gradle') {
                echo 'Start gradle build:'
                sh 'make build'
                echo 'Cloning gradle binaries to shared directory'
                sh "cp ./_build/*/libs/* ${env.SHARED_DIR}"
                echo 'Start static code analysis'
                sh 'make static-code-analysis'
              }
            }
          }
        }
      }

      stage('Publish java snapshots to artifactory') {
        when {
          allOf{
            expression { utils.verifySemVer(sem_ver: env.RELEASE_VERSION) }
          }
        }
        steps {
          withCredentials([
            string(credentialsId: 'artifactory_url', variable: 'ARTIFACTORY_URL'),
            string(credentialsId: 'artifactory_username', variable: 'ARTIFACTORY_USERNAME'),
            string(credentialsId: 'artifactory_password', variable: 'ARTIFACTORY_PASSWORD')
          ]) {
            container('gradle') {
              sh 'make publish'
            }
          }
        }
      }

      stage('Publish docker image if release branch and if repo is a service') {
        when {
          allOf{
            expression { utils.verifySemVer(sem_ver: env.RELEASE_VERSION) }
            expression { branchName.contains(env.ENZYME_PROJECT) }
            expression { utils.checkIfEnzymeService(env.ENZYME_PROJECT) }
          }
        }
        steps {
          withCredentials([
            usernamePassword(
              credentialsId: "GHENZYME_ARTIFACTORY_DOCKER_REGISTRY",
              usernameVariable: 'DOCKER_USER',
              passwordVariable: 'DOCKER_PASS',
            ),
          ]) {
            container('docker') {
              sh 'make docker-login'
              sh 'make docker-publish'
            }
          }
        }
      }

      stage('Trigger enzyme deployment job if release branch and if repo is a service') {
        when {
          allOf{
            expression { utils.verifySemVer(sem_ver: env.RELEASE_VERSION) }
            expression { branchName.contains(env.ENZYME_PROJECT) }
            expression { utils.checkIfEnzymeService(env.ENZYME_PROJECT) }
          }
        }
        steps {
          echo "Service discovered"
          build(
            job: "/deployments/enzyme",
            parameters: [
              string(name: 'ENVIRONMENT', value: env.TARGET_ENVIRONMENT),
              string(name: 'ENZYME_PROJECT', value: env.ENZYME_PROJECT),
              string(name: 'RELEASE_VERSION', value: env.DOCKER_TAG)
            ], quietPeriod: 2
          )
        }
      }
    }
    post {
      success {
        script {
          slack.notify(
            repositoryName: "enzyme-${env.ENZYME_PROJECT}",
            status: 'Success',
            additionalText: "",
            sendSlackNotification: env.SEND_SLACK_NOTIFICATION.toBoolean()
          )
        }
      }
      failure {
        script {
          slack.notify(
            repositoryName: "enzyme-${env.ENZYME_PROJECT}",
            status: 'Failure',
            additionalText: "",
            sendSlackNotification: env.SEND_SLACK_NOTIFICATION.toBoolean()
          )
        }
      }
    }
  }
}

String getReleaseVersion(String enzymeProject, String branchName) {
  List<String> splitBranch = branchName.split("${enzymeProject}-")
  (splitBranch.size() == 2) ? splitBranch[1].trim() : 'none'
}

String getManifest() {
  return """
apiVersion: v1
kind: Pod
metadata:
  labels:
    gh-jenkins-pod-type: docker
spec:
  securityContext:
    runAsUser: 0
  containers:
  - name: gradle
    securityContext:
      runAsUser: 0
    image: gradle:3.5-alpine
    command:
    - "sh"
    - "-c"
    - "apk update && apk add make && cat"
    tty: true
    resources:
      requests:
        memory: "512Mi"
        cpu: "100m"
      limits:
        memory: "3072Mi"
        cpu: "1500m"
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
    - mountPath: /shared
      name: shared
  - name: common-binaries
    image: ghi-ghinfra.jfrog.io/common-binaries:7e9c0db5
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
