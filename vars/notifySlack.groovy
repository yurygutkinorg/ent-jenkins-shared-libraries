def call(sendSlackNotification, repositoryName, status, additionalText) {
    // https://jenkins.io/doc/pipeline/examples/#slacknotify

    label = generateSlaveLabel()
    podTemplate(label: label, containers: [
        containerTemplate(
            name: 'gh-tools',
            image: 'pkprzekwas/all-in-one:8b85399',
            ttyEnabled: true,
            command: 'cat'
        ),
    ]) {
        node(label) {
            stage('Sending slack notification') {
                container('gh-tools') {
                    colorText 'Sending slack message:'

                    lastCommitScript = 'git --no-pager show -s --format="The last committer was %an/%ae, %ar%nCommit Message: %s%n"'
                    lastCommit = sh(returnStdout: true, script: lastCommitScript).trim()

                    barColor = '#36a64f'
                    if (status == 'Failure') {
                        barColor = '#f44242'
                    }

                    title = "Jenkins Build Status"
                    fallback = "Required plain-text summary of the attachment."
                    text = "Repository: ${repositoryName}\nBranch: ${BRANCH_NAME}\n${lastCommit}\n${additionalText}"

                    if (sendSlackNotification) {
                        withCredentials([string(
                            credentialsId: 'slack_enterprise_ci_bot',
                            variable: 'slack_enterprise_ci_bot')
                        ]) {
                            sh """
                                curl -X POST -H 'Content-type: application/json' \
                                  --data '{"attachments": [ \
                                    { \
                                      "fallback": "${fallback}", \
                                      "color": "${barColor}", \
                                      "title": "${title}", \
                                      "title_link": "${BUILD_URL}", \
                                      "text": "${text}", \
                                      "fields": [{ \
                                        "title": "Status", \
                                        "value": "${status}", \
                                        "short":false \
                                      }] \
                                    } \
                                  ]}' "${env.slack_enterprise_ci_bot}"
                            """
                        }
                    }
                }
            }
        }
    }
}
