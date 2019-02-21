def call() {
    sh(returnStdout: true, script: "git rev-parse HEAD | tr -d '\n'")
}
