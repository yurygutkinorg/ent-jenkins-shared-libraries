def colorText(Map args) {
    // NOTE: Color codes: http://pueblo.sourceforge.net/doc/manual/ansi_color_codes.html
    // 
    // Usage: 
    // `colorText color: 'green', text: 'Some msg'`
    
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
    lastCommitScript = 'git --no-pager show -s --format="The last committer was %an/%ae, %ar%nCommit Message: %s%n"'
    return sh(returnStdout: true, script: lastCommitScript).trim()
}

def getCurrentBranch() {
    if (env.GIT_BRANCH) {
        // if command is used in context of Multibranch Pipeline read value from ENV
        return env.GIT_BRANCH
    }
    branchFull = scm.branches[0].name.drop(2)
    splitBranchFull = branchFull.split('/')
    return splitBranchFull.last()
}

def getCommitID() {
    sh(returnStdout: true, script: "git rev-parse HEAD | tr -d '\n'")
}

def generateSlaveLabel() {
    if (env.BUILD_TAG) {
        // if command is used in context of Multibranch Pipeline read value from ENV
        return "${env.BUILD_TAG}"
    }
    return "slave-${UUID.randomUUID().toString()}"
}

def constrainLabelToSpecifications(String label) {
    if(label.length() >= 63){
        echo "Return truncated string so since it is not beyond the 63 character limit and not ending with non-alpha-numeric characters."
        def truncatedLabel = label.substring(0, 62)

        // Checking if trailing character is non alpha-numeric, and truncating until it is
        while(!Character.isLetter(truncatedLabel.charAt(truncatedLabel.length() - 1)) &&  !Character.isDigit(truncatedLabel.charAt(truncatedLabel.length() - 1))) {
            truncatedLabel = truncatedLabel.substring(0, truncatedLabel.length() - 1)
            echo "truncatedLabel ${truncatedLabel}"
        }
        return truncatedLabel
    }
    return label
}

def verifySemVer(Map args) {
    if (!args.sem_ver) {
        return false
    }
    // regex checks for XX.XX.XX, where X is a decimal
    return args.sem_ver.matches(/^([1-9]|[1-9][0-9])\.([0-9]|[1-9][0-9])\.([0-9]|[1-9][0-9])$/)
}