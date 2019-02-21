def call() {
    sh(returnStdout: true, script: 'git rev-parse --abbrev-ref HEAD').trim()
}
