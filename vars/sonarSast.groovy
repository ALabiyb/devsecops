// File: `vars/sonarSast.groovy`
def call(Map params = [:]) {
    try {
        echo "Stage: SonarQube - SAST"
        def sonarServer = params.sonarServer ?: env.SONARQUBE_SERVER
        withEnv(["JAVA_HOME=${params.javaHome ?: env.JAVA_17_HOME}"]) {
            withSonarQubeEnv(sonarServer) {
                sh params.command ?: "mvn sonar:sonar -Dsonar.projectKey=${params.projectKey ?: env.JOB_NAME}"
            }
            timeout(time: params.timeoutMinutes ?: 2, unit: 'MINUTES') {
                script { waitForQualityGate abortPipeline: true }
            }
        }
        return [success: true]
    } catch (e) {
        env.failedStage = "SonarQube - SAST"
        echo "SonarQube failed: ${e}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}
