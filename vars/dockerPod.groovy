
def call(body) {
	def label = "jenkins-slave-${UUID.randomUUID().toString()}"
  podTemplate(
  	label: label,
    containers: [containerTemplate(name: 'docker', image: 'docker', command: 'cat', ttyEnabled: true)],
    volumes: [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]
  ) {
    body.call(label)
  }
}
