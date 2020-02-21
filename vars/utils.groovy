Boolean isEnabled(Boolean flag) {
  flag != false || flag != null
}

void colorText(Map args) {
  // NOTE: Color codes: http://pueblo.sourceforge.net/doc/manual/ansi_color_codes.html
  //
  // Usage:
  // `util.colorText(color: 'green', text: 'Some msg')`
  Map colors = [red: "41", green: "42", yellow: "43", blue: "44", magenta: "45", cyan: "46", white: "47"]

  String selected = colors[args.color.toLowerCase()]
  if (selected == null) {
    selected = "47"
  }

  wrap([$class: 'AnsiColorBuildWrapper']) {
    echo "\u001B[${selected}m${args.text}\u001B[0m"
  }
}

String _wrap_git_command(cmd) {
  try {
    return sh(returnStdout: true, script: cmd).trim()
  } catch (Exception e) {
    echo "Error: ${e.message}"
    return 'GitDataFetchException'
  }
}

String getLastCommit() {
  _wrap_git_command('git show -q')
}

String getCurrentBranch() {
  // if command is used in context of Multibranch Pipeline read value from ENV
  if (env.GIT_BRANCH) { return env.GIT_BRANCH }

  try {
    String branchFull = scm.branches[0].name.drop(2)
    return branchFull.split('/').last()
  } catch (Exception e) {
    echo "Error: ${e.message}"
    return 'GitDataFetchException'
  }
}

String getCommitID() {
  _wrap_git_command("git rev-parse HEAD | tr -d '\n'")
}

String generateSlaveLabel() {
  // if command is used in context of Multibranch Pipeline read value from ENV
  if (env.BUILD_TAG) { return constrainLabelToSpecifications("${env.BUILD_TAG}") }
  return "slave-${UUID.randomUUID().toString()}"
}

String constrainLabelToSpecifications(String label) {
  if (label.length() >= 63) {
    String truncatedLabel = label.substring(0, 62)
    Integer length = truncatedLabel.length()

    // Checking if trailing character is non alpha-numeric, and truncating until it is
    while(!Character.isLetter(truncatedLabel.charAt(length - 1)) && !Character.isDigit(truncatedLabel.charAt(length - 1))) {
      truncatedLabel = truncatedLabel.substring(0, length - 1)
      length = truncatedLabel.length()
    }
    println("Truncated label: ${truncatedLabel}")
    return truncatedLabel
  }
  return label
}

Boolean verifySemVer(Map args) {
  if (!args.sem_ver) { return false }
  // regex checks for XX.XX.XX, where X is a decimal
  return args.sem_ver.matches(/^([1-9]|[1-9][0-9])\.([0-9]|[1-9][0-9])\.([0-9]|[1-9][0-9])$/)
}

Boolean checkIfEnzymeService(String serviceName) {
  List<String> serviceList = [
    "auth", "billing", "clinical", "clinical-study-report-generation",
    "curation", "exchange", "file", "finance", "ivd-reporting",
    "message", "misc", "omni-reporting", "problem-case", "iuo-reporting",
    "g361-reporting", "g360-ps-reporting", "g360-ldt-reporting"
  ]
  return serviceList.contains(serviceName)
}

void verifyImageChecksum(Map args) {
  String podNamePrefix = args.podNamePrefix
  String jFrogUser = args.jFrogUser
  String jFrogPass = args.jFrogPass
  String imageManifestPath = args.imageManifestPath
  String namespace = args.namespace
  Boolean debug = args.debug

  String checksumFromArtifactory = getPodDigestFromArtifactory(jFrogUser, jFrogPass, imageManifestPath, namespace)

  if (isEnabled(debug)) printPodStates(podNamePrefix, namespace)

  String podName = getRecentlyDeployedPod(podNamePrefix, namespace)
  if (isEnabled(debug)) echo("Most recently deployed pod: ${podName}")
  waitForReadyCondition(podName, namespace)
  List<String> checksumsFromK8s = getPodDigestsFromK8s(podName, namespace)

  echo("Digest from JFrog: ${checksumFromArtifactory}")
  echo("Digests from ${podName} pod: ${checksumsFromK8s}")

  if (checksumFromArtifactory in checksumsFromK8s) {
    echo('Checksum verification successful')
  } else {
    error("${checksumFromArtifactory} wasn't found in ${checksumsFromK8s}")
  }
}

String getRecentlyDeployedPod(String pattern, String namespace) {
  sh(
    script: """
      kubectl get -n ${namespace} po --sort-by=.status.startTime | \
        grep -v "Terminating" | \
        grep ${pattern} | \
        tac | \
        awk '{print \$1}' | \
        head -n1
    """,
    returnStdout: true,
  ).trim()
}

String getPodDigestFromArtifactory(String user, String pass, String imageManifestPath, String namespace) {
  String ghArtifactoryStorageApi = "https://ghi.jfrog.io/ghi/api/storage"
  sh(
    script: """
      curl -u ${user}:${pass} ${ghArtifactoryStorageApi}/${imageManifestPath} | jq -r .originalChecksums.sha256
    """,
    returnStdout: true,
  ).trim()
}

List<String> getPodDigestsFromK8s(String podName, String namespace) {
  String rawImageIDs = sh(
    script: """
      kubectl get -n ${namespace} po ${podName} -ojsonpath="{.status.containerStatuses[*].imageID}"
    """,
    returnStdout: true,
  )
  List<String> listedImageIDs = rawImageIDs.split(' ')
  List<String> digests = listedImageIDs.collect { extractDigestFromImageID(it) }
  digests
}

void waitForReadyCondition(String podName, String namespace) {
  String result = sh(
    script: "kubectl wait --for=condition=Initialized -n ${namespace} pod/${podName} --timeout=90s",
    returnStdout: true,
  )
  echo(result)
}

String extractDigestFromImageID(String imgID) {
  if (imgID.contains('sha256:')) {
    imgID.split('sha256:')[1]
  } else {
    error("Invalid image ID: \"${imgID}\". Pod might be still in pending state...")
  }
}

void printPodStates(String pattern, String namespace) {
  String runningPods = sh(
    script: "kubectl get pods -n ${namespace} | grep ${pattern}",
    returnStdout: true,
  )
  String recentlyStartedPods = sh(
    script: "kubectl get -n ${namespace} po --sort-by=.status.startTime | grep ${pattern}",
    returnStdout: true,
  )
  echo(runningPods)
  echo(recentlyStartedPods)
}

Boolean branchExists(String branchName, String repoAddr, String gitCredentialsId) {
  withCredentials([
    usernamePassword(credentialsId: gitCredentialsId, passwordVariable: 'gitPass', usernameVariable: 'gitUser')
  ]) {
    String cmd = "git ls-remote --heads https://${gitUser}:${gitPass}@${repoAddr} ${branchName} | wc -l"
    String remoteCnt = sh(script: cmd, returnStdout: true).trim()
    return remoteCnt != "0"
  }
}

void createBranch(String branchName, String repoAddr, String gitCredentialsId) {
  withCredentials([
    usernamePassword(credentialsId: gitCredentialsId, passwordVariable: 'gitPass', usernameVariable: 'gitUser')
  ]) {
    removeLocalBranch(branchName)
    String cmd = "git branch -d ${branchName} && git branch ${branchName} && git push https://${gitUser}:${gitPass}@${repoAddr} ${branchName}"
    Integer status = sh(script: cmd, returnStatus: true)
    if (status != 0) error("Couldn't create branch: ${branchName}")
  }
}

void removeLocalBranch(String branchName) {
    String cmd = "git branch -d ${branchName}"
    Integer status = sh(script: cmd, returnStatus: true)
    if (status != 0) echo("${branchName} doesn't exist yet")
}
