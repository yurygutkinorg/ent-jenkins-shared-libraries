def call() {
    if (env.GIT_BRANCH) {
        return env.GIT_BRANCH
    }
    return scm.branches[0].name.drop(2)
}
