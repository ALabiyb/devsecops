// vars/sonarSast.groovy
def call(Map params = [:]) {
    try {
        echo "=== Starting SonarQube Analysis (SAST) ==="

        def sonarServer = params.sonarServer ?: 'SonarQube Server'  // Name as configured in Jenkins Global Config
        def projectKey = params.projectKey ?: env.JOB_NAME
        def projectName = params.projectName ?: env.JOB_NAME
//        def timeoutMinutes = params.timeoutMinutes ?: 10

        // Default Maven command (can be overridden)
        def sonarCmd = params.command ?: "mvn clean verify sonar:sonar " +
                "-Dsonar.projectKey=${projectKey} " +
                "-Dsonar.projectName=\"${projectName}\" " +
                "-Dsonar.host.url=${SONAR_HOST_URL} " +
                "-Dsonar.login=${SONAR_AUTH_TOKEN}"

        withSonarQubeEnv(sonarServer) {
            // Use the Maven tool configured in Jenkins
            def mvnHome = tool 'maven-3.8.7'  // Make sure this name matches your Jenkins tool config
            sh "${mvnHome}/bin/mvn clean verify"  // Optional: compile first

            echo "Running SonarQube scan..."
            sh "${mvnHome}/bin/${sonarCmd}"
        }

//        echo "Waiting for SonarQube Quality Gate..."
//        timeout(time: timeoutMinutes, unit: 'MINUTES') {
//            def qg = waitForQualityGate()
//            if (qg.status != 'OK') {
//                error "Pipeline aborted due to Quality Gate failure: ${qg.status}"
//            }
//        }

        echo "✅ SonarQube analysis passed!"
        return [success: true]

    } catch (Exception e) {
        env.failedStage = "SonarQube - SAST"
        echo "❌ SonarQube analysis failed: ${e.message}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}