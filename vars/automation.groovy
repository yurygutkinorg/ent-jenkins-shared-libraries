import jenkins.model.Jenkins

def call(String testSuite, String browserType, String email, String projectName, String projectPath){
    pipeline {
        agent {
            kubernetes {
            label "${env.BUILD_TAG.toLowerCase().substring(17)}"
            defaultContainer 'katalon'
            yaml """
              apiVersion: v1
              kind: Pod
              metadata:
                labels:
                  name: dynamic-slave
              spec:
                containers:
                  - name: katalon
                    image: katalonstudio/katalon:7.4.0
                    command:
                      - cat
                    tty: true
            """
            }
        }
        parameters {
            string(defaultValue: 'sqa', description: 'environment name', name: 'ENVIRONMENT')
            string(defaultValue: "${email}", description: 'email', name: 'Email')
            string(defaultValue: "${testSuite}", description: 'test suite', name: 'TestSuite')
        }
        environment {
            ENVIRONMENT = "${params.ENVIRONMENT}"
        }

        stages {
            stage('Run Test Suite') {
                environment {
                    KATALON_API_KEY = credentials("KATALON_API_KEY")
                }
                steps {
                    container('katalon') {
                        script {
                          sh """
                            mkdir -p /tmp/katalon_execute/workspace/Results/download
                            mkdir -p /tmp/katalon_execute/project/Results/
                            ln -s /tmp/katalon_execute/workspace/Results/download /tmp/katalon_execute/project/Results/

                            mkdir -p /tmp/katalon_execute/project/Resources/
                            ln -s /tmp/katalon_execute/project/Resources/ /tmp/katalon_execute/workspace/

                            mkdir -p /tmp/katalon_execute/project/Data\\ Files/
                            ln -s /tmp/katalon_execute/project/Data\\ Files/ /tmp/katalon_execute/workspace/

                            cp settings/internal/${projectName}-${params.ENVIRONMENT}.properties settings/internal/com.kms.katalon.execution.properties
                
                            katalonc.sh \
                                -apiKey=${KATALON_API_KEY} \
                                -executionProfile=${ENVIRONMENT} \
                                -browserType="${browserType}" \
                                -retry=0 \
                                -statusDelay=15 \
                                -testSuitePath="Test Suites/${params.TestSuite}" \
                                -sendMail=${params.Email} \
                                -projectPath=${projectPath}
                          """
                        } 
                    }
                }
            }
        }
        post {
            success {
                echo 'Success'
                deleteDir()
            }
            failure {
                echo 'Failure'
            }
        }
    }
}
