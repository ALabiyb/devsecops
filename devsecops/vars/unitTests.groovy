// File: `vars/unitTests.groovy`
def call(Map params = [:]) {
    try {
        echo "Stage: Unit Tests - JUnit and JaCoCo"
        sh params.command ?: "mvn test"
        junit testResults: params.junitPattern ?: 'target/surefire-reports/*.xml', allowEmptyResults: true
        return [success: true]
    } catch (e) {
        env.failedStage = "Unit Tests - JUnit and JaCoCo"
        echo "Unit tests failed: ${e}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}
