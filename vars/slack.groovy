def notify(Map args) {
  // Usage:
  // `slack.notify(repositoryName: 'gh-aws', status: 'Success', additionalText: 'Some msg', sendSlackNotification: true)`
  if (!args.sendSlackNotification) return

  // https://jenkins.io/doc/pipeline/examples/#slacknotify
  String label = utils.constrainLabelToSpecifications("slack-${utils.generateSlaveLabel()}")
  String lastCommit = ""
  String branchName = ""

  container('jnlp') {
    lastCommit = utils.getLastCommit()
    branchName = utils.getCurrentBranch()
  }

  podTemplate(label: label, containers: [
    containerTemplate(
      name: 'gh-tools',
      image: 'ghi-ghinfra.jfrog.io/common-binaries:38f0265a',
      ttyEnabled: true,
      command: 'cat',
      resourceRequestCpu: '50m',
      resourceLimitCpu: '100m',
      resourceRequestMemory: '64Mi',
      resourceLimitMemory: '128Mi'
    ),
  ]) {
    node(label) {
      container('gh-tools') {
        String barColor = (args.status == 'Success') ? '#36a64f' :'#f44242'
        String title = "Jenkins Build Status"
        String fallback = "Required plain-text summary of the attachment."

        String text = """
Repository: ${args.repositoryName}
Branch: ${branchName}
Last commit: ${lastCommit}
${args.additionalText}
"""

        String rawData = """
{ "attachments": [ \
  { "fallback": "${fallback}", \
    "color": "${barColor}", \
    "title": "${title}", \
    "title_link": "${env.BUILD_URL}", \
    "text": "${text}", \
    "fields": [ { "title": "Status", "value": "${args.status}", "short": false } ] } \
]}
"""

        String data = rawData.replace("'", "")

        withCredentials([string(
          credentialsId: 'slack_enterprise_ci_bot',
          variable: 'slack_enterprise_ci_bot')
        ]) {
          String cmd = "curl -X POST -H 'Content-type: application/json' --data '${data}' '${env.slack_enterprise_ci_bot}'"
          Integer status = sh(script: cmd, returnStatus: true)
          if (status != 0) {
            error('Could not sent slack message.')
          } else {
            echo 'Slack message sent.'
          }
        }
      }
    }
  }
}
