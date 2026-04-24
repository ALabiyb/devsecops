// // vars/sonarSast.groovy
// def call(Map params = [:]) {
//     try {
//         echo "=== Starting SonarQube Analysis (SAST) ==="

//         def sonarServer    = params.sonarServer      ?: 'SonarQube Server'
//         def projectKey     = params.projectKey       ?: env.JOB_NAME
//         def projectName    = params.projectName      ?: env.JOB_NAME
//         def waitForQGate   = params.waitForQualityGate ?: false
//         def timeoutMinutes = params.timeoutMinutes   ?: 5

//         // Resolve tools
//         def mvnHome  = tool name: 'mave-3.9.15', type: 'maven'
//         def javaHome = tool name: 'jdk21',        type: 'jdk'

//         withEnv(["PATH+MAVEN=${mvnHome}/bin", "PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {

//             // Step 1: Run the SonarQube analysis using Maven
//             withSonarQubeEnv(sonarServer) {
//                 sh """mvn sonar:sonar \
//                     -Dsonar.projectKey=${projectKey} \
//                     -Dsonar.projectName="${projectName}" """
//             }

//             // Step 2: Wait for Quality Gate result
//             if (waitForQGate) {
//                 echo "Waiting for SonarQube Quality Gate result..."
//                 timeout(time: timeoutMinutes, unit: 'MINUTES') {
//                     def qg = waitForQualityGate()
//                     if (qg.status != 'OK') {
//                         error "SonarQube Quality Gate failed: ${qg.status}"
//                     }
//                 }
//                 echo "SonarQube Quality Gate passed."
//             }
//         }

//         echo "✅ SonarQube analysis completed and uploaded successfully!"
//         return [success: true]

//     } catch (Exception e) {
//         env.failedStage  = "SonarQube - SAST"
//         env.failedReason = e.getMessage()
//         echo "❌ SonarQube analysis or Quality Gate failed: ${e.message}"
//         currentBuild.result = 'FAILURE'
//         throw e
//     }
// }


// vars/sonarSast.groovy
// def call(Map params = [:]) {
//     try {
//         echo "=== Starting SonarQube Analysis (SAST) ==="

//         def sonarServer    = params.sonarServer         ?: 'SonarQube Server'
//         def projectKey     = params.projectKey          ?: env.JOB_NAME
//         def projectName    = params.projectName         ?: env.JOB_NAME
//         def waitForQGate   = params.waitForQualityGate  ?: false
//         def timeoutMinutes = params.timeoutMinutes      ?: 5
//         def language       = params.language            ?: autoDetectLanguage()

//         echo "Detected project language/type: ${language}"

//         withSonarQubeEnv(sonarServer) {
//             switch (language) {

//                 case 'maven':
//                     def mvnHome  = tool name: 'mave-3.9.15', type: 'maven'
//                     def javaHome = tool name: 'jdk21',       type: 'jdk'
//                     withEnv(["PATH+MAVEN=${mvnHome}/bin", "PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {
//                         sh """mvn sonar:sonar \
//                             -Dsonar.projectKey=${projectKey} \
//                             -Dsonar.projectName="${projectName}" """
//                     }
//                     break

//                 case 'gradle':
//                     def javaHome = tool name: 'jdk21', type: 'jdk'
//                     withEnv(["PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {
//                         sh """./gradlew sonar \
//                             -Dsonar.projectKey=${projectKey} \
//                             -Dsonar.projectName="${projectName}" """
//                     }
//                     break

//                 case 'python':
//                 case 'node':
//                 case 'javascript':
//                 case 'typescript':
//                 case 'generic':
//                 default:
//                     def scannerHome = tool name: 'SonarScanner', type: 'hudson.plugins.sonar.SonarRunnerInstallation'
//                     withEnv(["PATH+SCANNER=${scannerHome}/bin"]) {
//                         sh """sonar-scanner \
//                             -Dsonar.projectKey=${projectKey} \
//                             -Dsonar.projectName="${projectName}" \
//                             -Dsonar.sources=. """
//                     }
//                     break
//             }
//         }

//         // Wait for Quality Gate
//         if (waitForQGate) {
//             echo "Waiting for SonarQube Quality Gate result..."
//             timeout(time: timeoutMinutes, unit: 'MINUTES') {
//                 def qg = waitForQualityGate()
//                 if (qg.status != 'OK') {
//                     error "SonarQube Quality Gate failed: ${qg.status}"
//                 }
//             }
//             echo "✅ SonarQube Quality Gate passed."
//         }

//         echo "✅ SonarQube analysis completed successfully!"
//         return [success: true]

//     } catch (Exception e) {
//         env.failedStage  = "SonarQube - SAST"
//         env.failedReason = e.getMessage()
//         echo "❌ SonarQube analysis failed: ${e.message}"
//         currentBuild.result = 'FAILURE'
//         throw e
//     }
// }

// // -------------------------------------------------------
// // Auto-detect language by checking files in the workspace
// // -------------------------------------------------------
// def autoDetectLanguage() {
//     if (fileExists('pom.xml'))         return 'maven'
//     if (fileExists('build.gradle'))    return 'gradle'
//     if (fileExists('package.json'))    return 'node'
//     if (fileExists('requirements.txt') ||
//         fileExists('setup.py')         ||
//         fileExists('pyproject.toml'))  return 'python'
//     return 'generic'
// }


// vars/sonarSast.groovy
def call(Map params = [:]) {
    try {
        echo "=== Starting SonarQube Analysis (SAST) ==="

        def sonarServer    = params.sonarServer          ?: 'SonarQube Server'
        def projectKey     = params.projectKey           ?: env.JOB_NAME
        def projectName    = params.projectName          ?: env.JOB_NAME
        def waitForQGate   = params.waitForQualityGate   ?: false
        def timeoutMinutes = params.timeoutMinutes        ?: 5
        def language       = params.language             ?: autoDetectLanguage()
        def sources        = params.sources              ?: '.'
        def exclusions     = params.exclusions           ?: '**/node_modules/**,**/__pycache__/**,**/venv/**,**/.venv/**'

        echo "📌 Language detected/set: ${language}"

        withSonarQubeEnv(sonarServer) {

            if (language == 'maven') {
                // ── Java / Maven ──────────────────────────────────────────────
                def mvnHome  = tool name: 'mave-3.9.15', type: 'maven'
                def javaHome = tool name: 'jdk21',       type: 'jdk'
                withEnv(["PATH+MAVEN=${mvnHome}/bin", "PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {
                    sh """
                        mvn sonar:sonar \
                            -Dsonar.projectKey=${projectKey} \
                            -Dsonar.projectName="${projectName}"
                    """
                }

            } else if (language == 'gradle') {
                // ── Java / Gradle ─────────────────────────────────────────────
                def javaHome = tool name: 'jdk21', type: 'jdk'
                withEnv(["PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {
                    sh """
                        ./gradlew sonar \
                            -Dsonar.projectKey=${projectKey} \
                            -Dsonar.projectName="${projectName}"
                    """
                }

            } else {
                // ── Python / Node / Generic → SonarScanner CLI ────────────────
                def scannerHome = tool name: 'SonarScanner', type: 'hudson.plugins.sonar.SonarRunnerInstallation'
                withEnv(["PATH+SCANNER=${scannerHome}/bin"]) {
                    sh """
                        sonar-scanner \
                            -Dsonar.projectKey=${projectKey} \
                            -Dsonar.projectName="${projectName}" \
                            -Dsonar.sources=${sources} \
                            -Dsonar.exclusions="${exclusions}"
                    """
                }
            }
        }

        // ── Quality Gate ──────────────────────────────────────────────────────
        if (waitForQGate) {
            echo "⏳ Waiting for SonarQube Quality Gate..."
            timeout(time: timeoutMinutes, unit: 'MINUTES') {
                def qg = waitForQualityGate()
                if (qg.status != 'OK') {
                    error "❌ Quality Gate failed: ${qg.status}"
                }
            }
            echo "✅ Quality Gate passed."
        }

        echo "✅ SonarQube analysis completed successfully!"
        return [success: true]

    } catch (Exception e) {
        env.failedStage  = "SonarQube - SAST"
        env.failedReason = e.getMessage()
        echo "❌ SonarQube analysis failed: ${e.message}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}

def autoDetectLanguage() {
    if (fileExists('pom.xml'))          return 'maven'
    if (fileExists('build.gradle'))     return 'gradle'
    if (fileExists('package.json'))     return 'node'
    if (fileExists('requirements.txt') ||
        fileExists('setup.py')          ||
        fileExists('pyproject.toml'))   return 'python'
    return 'generic'
}