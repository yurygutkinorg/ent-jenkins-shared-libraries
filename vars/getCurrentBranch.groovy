def call() {
    describable.branchMatches(script.getProperty("env").getProperty("BRANCH_NAME"))
}
