#!groovy

def call(String enzymeProject, String optionalArg, String anotherOptionalArg) {
  pipeline {
    agent {
      kubernetes {
        label utils.constrainLabelToSpecifications(env.BUILD_ID)
        defaultContainer 'gradle'
        workspaceVolume dynamicPVC(requestsSize: '10Gi')
        yaml manifest()
      }
    }

    environment {
      ENZYME_PROJECT          = "${enzymeProject}"
      DOCKER_REGISTRY         = 'ghi-ghenzyme.jfrog.io'
      DOCKER_IMAGE            = "${env.DOCKER_REGISTRY}/enzyme-${enzymeProject}"
      DOCKER_TAG              = "${env.BRANCH_NAME}-${env.GIT_COMMIT.take(7)}-${env.BUILD_ID}"
      RELEASE_VERSION         = releaseVersion(enzymeProject, env.BRANCH_NAME)
      TARGET_ENVIRONMENT      = 'dev'
      SHARED_DIR              = "/shared/${enzymeProject}-${env.GIT_COMMIT.take(7)}-${env.BUILD_ID}/"
      SEND_SLACK_NOTIFICATION = 'true'
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

      stage('Metadata injection to config.properties') {
        steps {
          script {
            injectMetadata(env.ENZYME_PROJECT, env.RELEASE_VERSION, env.GIT_COMMIT, env.BUILD_ID)
          }
        }
      }

      stage('JFrog Artifactory') {
        environment {
          ARTIFACTORY_URL = 'https://ghi.jfrog.io/ghi/ghdna-release/'
          ARTIFACTORY_USERNAME = '_gradle-publisher'
          ARTIFACTORY_PASSWORD = credentials('artifactory_password')
        }
        stages {
          stage('Build, test and copy gradle binaries to shared directory') {
            steps {
              sh 'gradle clean --refresh-dependencies'
              sh 'gradle build -x test --refresh-dependencies'
              sh "cp -rf ./_build/*/libs/* ${env.SHARED_DIR}"
            }
          }

          stage('Static code analysis') {
            steps {
              withSonarQubeEnv('sonar') {
                sh 'gradle --info sonarqube --debug --stacktrace --no-daemon'
              }
            }
            post {
              always {
                archiveArtifacts(artifacts: "_build/**/*.html", fingerprint: true)
              }
            }
          }

          stage('Publish java snapshots') {
            when { expression { utils.verifySemVer(env.RELEASE_VERSION) } }
            environment {
              GUARDANTHEALTH_API = "${env.RELEASE_VERSION}"
            }
            steps {
              sh 'gradle publish'
              sh "RELEASE_VERSION=${env.DOCKER_TAG} gradle publish"
            }
          }
        }
      }

      stage('Docker build') {
        steps {
          withCredentials([
            usernamePassword(
              credentialsId: 'GHENZYME_ARTIFACTORY_DOCKER_REGISTRY',
              usernameVariable: 'DOCKER_USER',
              passwordVariable: 'DOCKER_PASS',
            ),
          ]) {
            container('docker') {
              sh 'docker login ${DOCKER_REGISTRY} -u ${DOCKER_USER} -p ${DOCKER_PASS}'
              sh "cp ${SHARED_DIR}/* ./deployment/docker"
              sh """
                docker build --no-cache --pull \
                  -f ./deployment/docker/Dockerfile \
                  -t ${env.DOCKER_IMAGE}:${env.DOCKER_TAG} \
                  -t ${env.DOCKER_IMAGE}:${env.RELEASE_VERSION} \
                  ./deployment/docker
              """
            }
          }
        }
      }

      stage('Publish docker image') {
        when {
          anyOf {
            expression { shouldTriggerDeploy(env.ENZYME_PROJECT, env.BRANCH_NAME, env.RELEASE_VERSION) }
            expression { shouldBuildCandidate(env.ENZYME_PROJECT, env.BRANCH_NAME) } // used for testing
          }
        }
        steps {
          withCredentials([
            usernamePassword(
              credentialsId: 'GHENZYME_ARTIFACTORY_DOCKER_REGISTRY',
              usernameVariable: 'DOCKER_USER',
              passwordVariable: 'DOCKER_PASS',
            ),
          ]) {
            container('docker') {
              sh 'docker login ${DOCKER_REGISTRY} -u ${DOCKER_USER} -p ${DOCKER_PASS}'
              sh "docker push ${env.DOCKER_IMAGE}:${env.DOCKER_TAG}"
              sh "docker push ${env.DOCKER_IMAGE}:${env.RELEASE_VERSION}"
            }
          }
        }
        post {
          success {
            sh "echo '${env.DOCKER_TAG}' > docker_image_tag.txt"
            archiveArtifacts(artifacts: 'docker_image_tag.txt', fingerprint: true)
          }
        }
      }

      stage('Trigger enzyme deployment job') {
        when {
          expression { shouldTriggerDeploy(env.ENZYME_PROJECT, env.BRANCH_NAME, env.RELEASE_VERSION) }
        }
        steps {
          build(
            job: '/deployments/argocd-update-image-tag',
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
            additionalText: '',
            sendSlackNotification: env.SEND_SLACK_NOTIFICATION.toBoolean()
          )
        }
      }
      failure {
        script {
          slack.notify(
            repositoryName: "enzyme-${env.ENZYME_PROJECT}",
            status: 'Failure',
            additionalText: '',
            sendSlackNotification: env.SEND_SLACK_NOTIFICATION.toBoolean()
          )
        }
      }
    }
  }
}

Boolean shouldBuildCandidate(String enzymeProject, String branchName) {
  return branchName.startsWith('candidate-') && checkIfEnzymeService(enzymeProject)
}

Boolean shouldTriggerDeploy(String enzymeProject, String branchName, String releaseVersion) {
  return (
    utils.verifySemVer(releaseVersion) &&
    branchName.contains(enzymeProject) &&
    checkIfEnzymeService(enzymeProject)
  )
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
    "fax",
    "mrd-order-scheduler"
  ]
}

String releaseVersion(String enzymeProject, String branchName) {
  if (branchName.startsWith('candidate-')) {
    return '99.99.99' // used for testing
  }
  List<String> splitBranch = branchName.split("${enzymeProject}-")
  return (splitBranch.size() == 2) ? splitBranch[1].trim() : 'none'
}

Boolean checkIfEnzymeService(String serviceName) {
  return enzymeAppNamesList().contains(serviceName)
}

void injectMetadata(String appName, String releaseVersion, String gitCommit, String buildId) {
  String propertiesFilePaths = sh(
    script: 'find ./src/resources/config  -name config.properties',
    returnStdout: true
  ).trim()

  if (propertiesFilePaths == '') {
    error('config.properties does not exist under /src/resources/config.')
  }

  propertiesFilePaths.split('\n').each {
    sh """
      cat <<EOF >> ${it}

# BUILD METADATA
${appName}.build=${releaseVersion}-${gitCommit.take(7)}-${buildId}

EOF
    """
  }
}

String manifest() {
  return '''
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
    - cat
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
    - mountPath: /shared
      name: shared
  volumes:
  - name: socket
    hostPath:
      path: /var/run/docker.sock
  - name: shared
    hostPath:
      path: /tmp
'''
}
