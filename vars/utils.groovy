def colorText(Map args) {
  // NOTE: Color codes: http://pueblo.sourceforge.net/doc/manual/ansi_color_codes.html
  // 
  // Usage: 
  // `util.colorText(color: 'green', text: 'Some msg')`
  Map colors = [red: "41", green: "42", yellow: "43", blue: "44", magenta: "45", cyan: "46", white: "47"]

  def selected = colors[args.color.toLowerCase()]
  if (selected == null) {
    selected = "47"
  }

  wrap([$class: 'AnsiColorBuildWrapper']) {
    echo "\u001B[${selected}m${args.text}\u001B[0m"
  }
}

def getLastCommit() {
  return sh(returnStdout: true, script: 'git show -q').trim()
}

def getCurrentBranch() {
  // if command is used in context of Multibranch Pipeline read value from ENV
  if (env.GIT_BRANCH) { return env.GIT_BRANCH } 
  branchFull = scm.branches[0].name.drop(2)
  splitBranchFull = branchFull.split('/')
  return splitBranchFull.last()
}

def getCommitID() {
  sh(returnStdout: true, script: "git rev-parse HEAD | tr -d '\n'")
}

def generateSlaveLabel() {
  // if command is used in context of Multibranch Pipeline read value from ENV
  if (env.BUILD_TAG) { return constrainLabelToSpecifications("${env.BUILD_TAG}") }
  return "slave-${UUID.randomUUID().toString()}"
}

def constrainLabelToSpecifications(String label) {
  if (label.length() >= 63) {
    echo "Return truncated string so since it is not beyond the 63 character limit and not ending with non-alpha-numeric characters."
    def truncatedLabel = label.substring(0, 62)
    def length = truncatedLabel.length()
    // Checking if trailing character is non alpha-numeric, and truncating until it is
    while(!Character.isLetter(truncatedLabel.charAt(length - 1)) &&  !Character.isDigit(truncatedLabel.charAt(length - 1))) {
      truncatedLabel = truncatedLabel.substring(0, length - 1)
      length = truncatedLabel.length()
    }
    echo "truncatedLabel = ${truncatedLabel}"
    return truncatedLabel
  }
  return label
}

def verifySemVer(Map args) {
  if (!args.sem_ver) { return false }
  // regex checks for XX.XX.XX, where X is a decimal
  return args.sem_ver.matches(/^([1-9]|[1-9][0-9])\.([0-9]|[1-9][0-9])\.([0-9]|[1-9][0-9])$/)
}

def checkIfEnzymeService(String serviceName) {
  def serviceList = ["auth", "billing", "clinical", "curation", "exchange", "file", "finance", "ivd-reporting", "message", "misc", "omni-reporting", "problem-case", "iuo-reporting"]
  return serviceList.contains(serviceName)
}
