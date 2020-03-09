import jenkins.model.Jenkins

def call(String testSuite, String browserType, String email, String projectName){
    pipeline {
        agent {
            kubernetes {
            label "${env.BUILD_TAG.toLowerCase().substring(17)}"
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
            string(defaultValue: "${email}", description: 'email', name: 'Email')
            string(defaultValue: "${testSuite}", description: 'test suite', name: 'TestSuite')
        }
        environment {
            ENVIRONMENT      = "${params.ENVIRONMENT}"
        }

        String propertyFile = "${projectName}" + "_" + "${params.ENVIRONMENT}" + ".properties"

        stages {
            stage('Run Test Suite') {
                environment {
                    KATALON_API_KEY     = credentials("KATALON_API_KEY")
                }
                steps {
                    container('katalon') {
			            sh 'mkdir -p /tmp/katalon_execute/{workspace/Results/download,project/Resources/Results/download,project/Results/}'
                        sh 'ln -s /tmp/katalon_execute/project/Resources/ /tmp/katalon_execute/workspace/'
                        sh 'ln -s /tmp/katalon_execute/workspace/Results/download /tmp/katalon_execute/project/Results/'
                        
			script {
                            sh """
                                cp "${propertyFile}" settings/internal/com.kms.katalon.execution.properties
                
                                katalonc.sh \
                                -apiKey="${KATALON_API_KEY}" \
                                -projectPath="${projectPath}"           	 	
                                -executionProfile="${ENVIRONMENT}" \
                                -browserType="${browserType}" \
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

