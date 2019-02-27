def call() {
    if (env.GIT_BRANCH) {
        return env.GIT_BRANCH
    }

    branchFull = scm.branches[0].name.drop(2)
    splitBranchFull = branchFull.split('/')
    return splitBranchFull.last()
}
