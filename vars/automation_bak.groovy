import jenkins.model.Jenkins

def slave_label = "jenkins-slave-test-${UUID.randomUUID().toString()}"

def call(String testSuite, String settingsFile, String browserType){
    pipeline {
        agent {
            kubernetes {
            label slave_label
            defaultContainer 'jnlp'
            yaml """
        apiVersion: v1
        kind: Pod
        metadata:
        labels:
            name: dynamic-slave
        spec:
        containers:
        - name: katalon
            image: katalonstudio/katalon
            command:
            - cat
            tty: true
        """
            }
        }
        parameters {
            string(defaultValue: 'sqa', description: 'environment name', name: 'ENVIRONMENT')
            string(defaultValue: 'gxu@guardanthealth.com', description: 'email', name: 'Email')
            string(defaultValue: 'ETrfSuite', description: 'test suite', name: 'TestSuite')
        }
        environment {
            ENVIRONMENT      = "${params.ENVIRONMENT}"
        }
        stages {
            stage('Run Test Suite') {
            when { 
                anyOf { 
                expression {ENVIRONMENT == 'sqa'} 
                expression {ENVIRONMENT == 'val'}
                expression {ENVIRONMENT == 'prod'}
                }
            }
            environment {
                KATALON_API_KEY     = credentials("KATALON_API_KEY")
            }
                steps {
                container('katalon') {
                    sh 'mkdir -p /tmp/katalon_execute/workspace/'
                    sh 'mkdir -p /tmp/katalon_execute/project/Resources/'
                    sh 'ln -s /tmp/katalon_execute/project/Resources/ /tmp/katalon_execute/workspace/'
                    
                    sh 'mkdir -p /tmp/katalon_execute/workspace/Results/download'
                    sh 'mkdir -p /tmp/katalon_execute/project/Results/'
                    sh 'ln -s /tmp/katalon_execute/workspace/Results/download /tmp/katalon_execute/project/Results/'

                    script {
                    if (params.ENVIRONMENT == 'val'){
                        sh """
                            cd settings/internal
                            cp api-val.properties com.kms.katalon.execution.properties
                            cd ../..
                            katalonc.sh \
                            -apiKey="${KATALON_API_KEY}" \
                            -projectPath="/home/jenkins/agent/workspace/test-automation-jobs/ent-api-automation"           	 	
                            -executionProfile="${ENVIRONMENT}" \
                            -browserType="Web Service" \
                            -retry=0 \
                            -statusDelay=15 \
                            -testSuitePath="Test Suites/Portal/${params.TestSuite}" \
                            -sendMail="${params.Email}"                
                        """
                    } else if (params.ENVIRONMENT == 'sqa') {
                        sh """
                            cd settings/internal
                            cp api-sqa.properties com.kms.katalon.execution.properties
                            cd ../..
                            katalonc.sh \
                            -apiKey="${KATALON_API_KEY}" \
                            -projectPath="/home/jenkins/agent/workspace/test-automation-jobs/ent-api-automation" \
                            -executionProfile="${ENVIRONMENT}" \
                            -browserType="Web Service" \
                            -retry=0 \
                            -statusDelay=15 \
                            -testSuitePath="Test Suites/Portal/${params.TestSuite}" \
                            -sendMail="${params.Email}"
                        """
                    }
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
