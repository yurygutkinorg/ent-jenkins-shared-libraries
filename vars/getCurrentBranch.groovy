def call() {
    if (env.GIT_BRANCH) {
        // if command is used in context of Multibranch Pipeline read value from ENV
        return env.GIT_BRANCH
    }

    branchFull = scm.branches[0].name.drop(2)
    splitBranchFull = branchFull.split('/')
    return splitBranchFull.last()
}
