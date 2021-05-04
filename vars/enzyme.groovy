
def call(String enzymeProject, String branchName, String buildTag) {
  String shortBuildTag = utils.constrainLabelToSpecifications(buildTag)
  String releaseVersion = getReleaseVersion(enzymeProject, branchName)

  pipeline {
    agent {
      kubernetes {
        label "${shortBuildTag}"
        defaultContainer 'common-binaries'
        workspaceVolume dynamicPVC(requestsSize: '10Gi')
        yaml getManifest()
      }
    }

    environment {
      ENZYME_PROJECT          = "${enzymeProject}"
      DOCKER_TAG              = "${branchName}-${env.GIT_COMMIT.take(7)}-${env.BUILD_ID}"
      RELEASE_VERSION         = "${releaseVersion}"
      TARGET_ENVIRONMENT      = "dev"
      SHARED_DIR              = "/shared/${buildTag}/"
      SEND_SLACK_NOTIFICATION = "true"
    }

    stages {
      stage('Set build name') {
        steps {
          script {
            currentBuild.displayName = "${env.RELEASE_VERSION}-${env.GIT_COMMIT.take(7)}-${env.BUILD_ID}"
            if (
              shouldTriggerDeploy(env.ENZYME_PROJECT, env.BRANCH_NAME, env.RELEASE_VERSION) ||
              shouldBuildCandidate(env.ENZYME_PROJECT, env.BRANCH_NAME)
            ) {
              currentBuild.description = "Docker tag: ${env.DOCKER_TAG}"
            }
          }
        }
      }

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

            PROPERTIES_FILE_PATH.split('\n').each() {
              sh """
                cat <<EOF >> ${it}

# BUILD METADATA
${env.ENZYME_PROJECT}.build=${env.RELEASE_VERSION}-${env.GIT_COMMIT.take(7)}-${env.BUILD_ID}

EOF
              """
            }
          }
        }
      }

      stage('Build, test and copy gradle binaries to shared directory') {
        steps {
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
            }
          }
        }
      }

      stage('Static code analysis') {
        steps {
          withSonarQubeEnv('sonar') {
            withCredentials([
              string(credentialsId: 'artifactory_url', variable: 'ARTIFACTORY_URL'),
              string(credentialsId: 'artifactory_username', variable: 'ARTIFACTORY_USERNAME'),
              string(credentialsId: 'artifactory_password', variable: 'ARTIFACTORY_PASSWORD')
            ]) {
              container('gradle') {
                echo 'Start static code analysis'
                sh 'gradle --info sonarqube --debug --stacktrace --no-daemon'
              }
            }
          }
        }
      }

      stage('Publish java snapshots to artifactory') {
        when {
          allOf{
            expression { utils.verifySemVer(env.RELEASE_VERSION) }
          }
        }
        steps {
          withCredentials([
            string(credentialsId: 'artifactory_url', variable: 'ARTIFACTORY_URL'),
            string(credentialsId: 'artifactory_username', variable: 'ARTIFACTORY_USERNAME'),
            string(credentialsId: 'artifactory_password', variable: 'ARTIFACTORY_PASSWORD')
          ]) {
            container('gradle') {
              sh "make publish"
              sh "RELEASE_VERSION=${DOCKER_TAG} make publish"
            }
          }
        }
      }

      stage('Publish docker image') {
        when {
          anyOf {
            expression { shouldTriggerDeploy(env.ENZYME_PROJECT, branchName, env.RELEASE_VERSION) }
            expression { shouldBuildCandidate(env.ENZYME_PROJECT, branchName) } // used for testing
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
        post {
          success {
            sh "echo '${env.DOCKER_TAG}' > docker_image_tag.txt"
            archiveArtifacts artifacts: 'docker_image_tag.txt', fingerprint: true
          }
        }
      }

      stage('Trigger enzyme deployment job') {
        when {
          expression { shouldTriggerDeploy(env.ENZYME_PROJECT, branchName, env.RELEASE_VERSION) }
        }
        steps {
          echo "Service discovered"
          build(
            job: "/deployments/argocd-update-image-tag",
            parameters: [
              string(name: 'APP_NAME', value: env.ENZYME_PROJECT),
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

Boolean shouldBuildCandidate(String enzymeProject, String branchName) {
  branchName.startsWith('candidate-') && checkIfEnzymeService(enzymeProject)
}

Boolean shouldTriggerDeploy(String enzymeProject, String branchName, String releaseVersion) {
  utils.verifySemVer(releaseVersion) && branchName.contains(enzymeProject) && checkIfEnzymeService(enzymeProject)
}

List<String> getEnzymeAppNamesList() {
  [
    "auth",
    "billing",
    "clinical",
    "clinical-study-report-generation",
    "curation",
    "exchange",
    "file",
    "finance",
    "g360-reporting",
    "g360-ps-reporting",
    "g360-ldt-reporting",
    "g360response-ldt-reporting",
    "ivd-reporting",
    "iuo-reporting",
    "lims-data-service",
    "message",
    "misc",
    "omni-reporting",
    "omni-ldt-reporting",
    "problem-case",
    "lunar1-ldt-reporting",
    "tissue-reporting",
    "pdl1-reporting",
    "fax"
  ]
}

String getReleaseVersion(String enzymeProject, String branchName) {
  if (branchName.startsWith('candidate-')) {
    return '99.99.99' // used for testing
  }
  List<String> splitBranch = branchName.split("${enzymeProject}-")
  (splitBranch.size() == 2) ? splitBranch[1].trim() : 'none'
}

Boolean checkIfEnzymeService(String serviceName) {
  getEnzymeAppNamesList().contains(serviceName)
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
    env:
    - name: _JAVA_OPTIONS
      value: -Xms512m -Xmx4096m
    - name: GRADLE_OPTS
      value: -Dorg.gradle.jvmargs="-Xmx4096m -XX:+HeapDumpOnOutOfMemoryError"
    securityContext:
      runAsUser: 0
      fsGroup: 1000
    image: gradle:4.10.3-alpine
    command:
    - "sh"
    - "-c"
    - "apk update && apk add make && cat"
    tty: true
    resources:
      requests:
        memory: "1024Mi"
        cpu: "100m"
      limits:
        memory: "8192Mi"
        cpu: "2"
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
    image: ghi-ghinfra.jfrog.io/common-binaries:057fc8f9
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
