def notify(Map args) {
  // Usage:
  // `slack.notify(repositoryName: 'gh-aws', status: 'Success', additionalText: 'Some msg', sendSlackNotification: true)`
  if (!args.sendSlackNotification) { return }

  // https://jenkins.io/doc/pipeline/examples/#slacknotify
  label = utils.constrainLabelToSpecifications("slack-${utils.generateSlaveLabel()}")

  podTemplate(label: label, containers: [
    containerTemplate(
      name: 'gh-tools',
      image: 'ghinfra/common-binaries:7',
      ttyEnabled: true,
      command: 'cat'
    ),
  ]) {
    node(label) {
      container('gh-tools') {
        barColor = (args.status == 'Success') ? '#36a64f' :'#f44242' 
        lastCommit = utils.getLastCommit()
        branchName = utils.getCurrentBranch()
        title = "Jenkins Build Status"
        fallback = "Required plain-text summary of the attachment."
        text = """
Repository: ${args.repositoryName}
Branch: ${branchName}
${lastCommit}
${args.additionalText}
"""
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
curl -X POST -H 'Content-type: application/json' --data '${data}' "${env.slack_enterprise_ci_bot}"
          """
        }
        echo 'Slack message sent.'
      }
    }
  }
}
