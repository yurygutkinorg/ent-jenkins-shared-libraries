def simple(String msg = 'An empty message.') {
    echo "INFO: ${msg}"
}

def slack(sendSlackNotification, repositoryName, status, lastCommit, additionalText) {
    // https://jenkins.io/doc/pipeline/examples/#slacknotify

    colorText 'Sending slack message:'

    barColor = '#36a64f'
    if (status == 'Failure') {
        barColor = '#f44242'
    }

    title = "Jenkins Build Status"
    fallback = "Required plain-text summary of the attachment."
    text = "Repository: ${repositoryName}\nBranch: ${BRANCH_NAME}\n${lastCommit}\n${additionalText}"

    if (sendSlackNotification) {
        withCredentials([
            string(
                credentialsId: 'slack_enterprise_ci_bot',
                variable: 'slack_enterprise_ci_bot'
            )
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
