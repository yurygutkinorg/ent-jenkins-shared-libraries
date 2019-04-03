def notify(Map args) {
    // Usage: 
    // `notifySlack repositoryName: 'gh-aws', status: 'Success', additionalText: 'Some msg', sendSlackNotification: true`

    if (!args.sendSlackNotification) {
        return
    }

    barColor = '#36a64f'
    if (args.status == 'Failure') {
        barColor = '#f44242'
    }
    
    // https://jenkins.io/doc/pipeline/examples/#slacknotify
    label = "slack-${utils.generateSlaveLabel()}"
    lastCommit = utils.getLastCommit()

    podTemplate(label: label, containers: [
        containerTemplate(
            name: 'gh-tools',
            image: 'ghinfra/common-binaries:2',
            ttyEnabled: true,
            command: 'cat'
        ),
    ]) {
        node(label) {
            container('gh-tools') {
                branchName = utils.getCurrentBranch()
                title = "Jenkins Build Status"
                fallback = "Required plain-text summary of the attachment."
                text = "Repository: ${args.repositoryName}\nBranch: ${branchName}\n${lastCommit}\n${args.additionalText}"
                data = """
                { \
                    "attachments": [ \
                        { \
                            "fallback": "${fallback}", \
                            "color": "${barColor}", \
                            "title": "${title}", \
                            "title_link": "${env.BUILD_URL}", \
                            "text": "${text}", \
                            "fields": [ { "title": "Status", "value": "${args.status}", "short": false } ] \
                        } \
                    ] \
                }
                """
                withCredentials([string(
                    credentialsId: 'slack_enterprise_ci_bot',
                    variable: 'slack_enterprise_ci_bot')
                ]) {
                    sh """
                        curl -X POST -H 'Content-type: application/json' \
                            --data '${data}' "${env.slack_enterprise_ci_bot}"
                    """
                }
                utils.colorText(color: 'green', text: 'Slack message sent.')      
            }
        }
    }
}