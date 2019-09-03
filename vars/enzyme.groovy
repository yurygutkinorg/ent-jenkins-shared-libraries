
def call(String enzyme_project, String branch_name, String build_tag) {
  pipeline {
    agent {
      kubernetes {
        label "${build_tag}"
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
              - name: gradle
                securityContext:
                  runAsUser: 0
                image: gradle:3.5-alpine
                command:
                - "sh"
                - "-c"
                - "apk update && apk add make && cat"
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
                image: ghinfra/common-binaries:2
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
      ENZYME_PROJECT          = "${enzyme_project}"
      REPO_NAME               = "enzyme-${enzyme_project}"
      SHARED_DIR              = "/shared/${build_tag}/"
      GIT_COMMIT              = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
      DOCKER_TAG              = "${branch_name}-${GIT_COMMIT}"
      SEND_SLACK_NOTIFICATION = true
      RELEASE_VERSION         = "none"
      TARGET_ENVIRONMENT      = "dev"
    }

    stages {
      stage('Create shared dir') {
        steps {
          sh "mkdir -p ${env.SHARED_DIR}"
          sh "echo test"
        }
      }

      stage('Check if release branch and assign release version from branch name') {
        when {
          expression {branch_name.split("${enzyme_project}-").size() == 2}
        }
        steps {
          script{
            RELEASE_VERSION = branch_name.split("${enzyme_project}-")[1].trim()
          }
        }
      }

      stage('Checkout gh-aws repo, move Makefile to workspace, and cleanup of gh-aws repo') {
        steps {
          dir('gh-aws') {
            git(
              url: 'https://github.com/guardant/gh-aws.git',
              credentialsId: 'ghauto-github',
              branch: "sqa"
            )
            echo 'Cloning Makefile to job workspace:'
            sh "cp ./deployment-scripts/enzyme/Makefile ${env.WORKSPACE}"
            echo 'Cleanup gh-aws project:'
            deleteDir()
          }
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

      stage('Publish snapshots to artifactory') {
        when {
          allOf{
            expression { utils.verifySemVer(sem_ver: RELEASE_VERSION) }
          }
        }

        steps {
          withCredentials([
          string(credentialsId: 'artifactory_url', variable: 'ARTIFACTORY_URL'),
          string(credentialsId: 'artifactory_username', variable: 'ARTIFACTORY_USERNAME'),
          string(credentialsId: 'artifactory_password', variable: 'ARTIFACTORY_PASSWORD')
        ]) {
            withEnv(["RELEASE_VERSION=${RELEASE_VERSION}"]){
              container('gradle') {
                echo "RELEASE_VERSION ${RELEASE_VERSION}"
                echo "env.RELEASE_VERSION ${env.RELEASE_VERSION}"
                echo 'Push snapshots to artifactory'
                sh 'make publish'
              }
            }
          }
        }
      }

      stage('Check if release branch and check if repo is a service, Login to Docker registry, build and publish docker image') {
        when {
          allOf{
            expression { utils.verifySemVer(sem_ver: RELEASE_VERSION) }
            expression { branch_name.contains(enzyme_project) }
            expression { utils.checkIfEnzymeService(enzyme_project) }
          }
        }
        steps {
          withCredentials([
            usernamePassword(credentialsId: "GHENZYME_ARTIFACTORY_DOCKER_REGISTRY", usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')
          ]) {
            withEnv(["RELEASE_VERSION=${RELEASE_VERSION}"]){
              container('docker') {
                echo 'Login to docker registry'
                sh 'make docker-login'
                echo 'Build and publish docker image'
                sh 'make docker-publish'
                }
              }
            }
          }
        }

      stage('Check if release branch and check if repo is a service, Trigger enzyme deployment job') {
        when {
          allOf{
            expression { utils.verifySemVer(sem_ver: RELEASE_VERSION) }
            expression { branch_name.contains(enzyme_project) }
            expression { utils.checkIfEnzymeService(enzyme_project) }
          }
        }
        steps {
          build(
            job: "/deployments/enzyme",
            parameters: [
              string(name: 'ENVIRONMENT', value: env.TARGET_ENVIRONMENT),
              string(name: 'ENZYME_PROJECT', value: enzyme_project),
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
