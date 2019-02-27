def call() {
    lastCommitScript = 'git --no-pager show -s --format="The last committer was %an/%ae, %ar%nCommit Message: %s%n"'
    return sh(returnStdout: true, script: lastCommitScript).trim()
}
