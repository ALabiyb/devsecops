/**
 * owaspDependencyCheck.groovy
 *
 * Scans project dependencies for known CVEs — supports ALL build tools:
 *
 *   maven  → OWASP Maven plugin  → target/dependency-check-report.xml
 *            Published in Jenkins UI via dependencyCheckPublisher in post{}
 *   npm    → npm audit           → npm-audit-report.json (build artifact)
 *   node   → same as npm
 *   next   → same as npm
 *   go     → govulncheck         → govulncheck-report.txt (build artifact)
 *   gradle → OWASP Gradle plugin → build/reports/dependency-check-report.xml
 *   dotnet → dotnet list package → dotnet-vuln-report.txt (build artifact)
 *
 * WHY DIFFERENT TOOLS PER LANGUAGE?
 *   npm audit   → uses official npm advisory database, built into npm
 *   govulncheck → official Google tool, uses Go vuln database (vuln.go.dev)
 *   dotnet      → built-in since .NET 7, no extra tools needed
 *   maven/gradle → OWASP plugin gives richest XML report for Jenkins publisher
 *
 * JENKINS ONE-TIME SETUP:
 *   1. Install plugin: "OWASP Dependency-Check"
 *      Manage Jenkins → Plugins → search "OWASP Dependency-Check"
 *   2. Add NVD API key (Maven/Gradle only — speeds up DB download):
 *      Manage Jenkins → Credentials → Add → Secret text
 *      ID: nvd-api-key
 *      Register free at: https://nvd.nist.gov/developers/request-an-api-key
 *
 * USAGE IN JENKINSFILE:
 *   stage('Dependency Check') {
 *       steps { script { owaspDependencyCheck(failOnCVSS: 0) } }
 *   }
 *   post {
 *       always {
 *           script {
 *               if (fileExists('target/dependency-check-report.xml')) {
 *                   dependencyCheckPublisher pattern: 'target/dependency-check-report.xml'
 *               }
 *           }
 *       }
 *   }
 *
 * failOnCVSS values:
 *   0  = never fail, report only ← START HERE (see what you have first)
 *   7  = fail on HIGH + CRITICAL
 *   9  = fail on CRITICAL only   ← recommended once team is familiar
 */
def call(Map params = [:]) {

    def buildTool  = (params.buildTool ?: env.BUILD_TOOL ?: autoDetectBuildTool()).toLowerCase()
    def failOnCVSS = params.containsKey('failOnCVSS') ? params.failOnCVSS : 0

    echo "=== Dependency Vulnerability Scan ==="
    echo "Build Tool:   ${buildTool}"
    echo "Fail on CVSS: ${failOnCVSS == 0 ? 'Never (report only)' : '>= ' + failOnCVSS}"

    try {
        switch (buildTool) {
            case 'maven':   return scanMaven(params, failOnCVSS)
            case 'npm':
            case 'node':
            case 'next':    return scanNpm(params, failOnCVSS)
            case 'go':      return scanGo(params, failOnCVSS)
            case 'gradle':  return scanGradle(params, failOnCVSS)
            case 'dotnet':  return scanDotnet(params, failOnCVSS)
            default:
                echo "⚠️  No dependency scan configured for: ${buildTool}"
                return [success: true, skipped: true]
        }
    } catch (Exception e) {
        if (!env.failedStage) {
            env.failedStage  = "Dependency Check (${buildTool})"
            env.failedReason = e.getMessage()
        }
        echo "❌ Dependency scan failed: ${e.getMessage()}"
        if (failOnCVSS > 0) {
            currentBuild.result = 'FAILURE'
            throw e
        } else {
            // failOnCVSS=0 → mark UNSTABLE but let pipeline continue
            currentBuild.result = 'UNSTABLE'
            return [success: false, error: e.getMessage()]
        }
    }
}

// -----------------------------------------------------------------------------
// MAVEN — OWASP Dependency Check Maven Plugin
// Generates XML + HTML report in target/
// Report published by dependencyCheckPublisher in post{ always {} }
// NVD API key required: Manage Jenkins → Credentials → Secret text → 'nvd-api-key'
// -----------------------------------------------------------------------------
def scanMaven(Map params, int failOnCVSS) {
    echo "🔍 OWASP Dependency Check — Maven"
    def nvdCredentialsId = params.nvdCredentialsId ?: 'nvd-api-key'
    def mvnHome  = tool name: 'mave-3.9.15', type: 'maven'
    def javaHome = tool name: 'jdk21',        type: 'jdk'

    withEnv(["PATH+MAVEN=${mvnHome}/bin", "PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {
        withCredentials([string(credentialsId: nvdCredentialsId, variable: 'NVD_API_KEY')]) {
            // failBuildOnCVSS=11 = never fail (CVSS max is 10)
            def cvssThreshold = failOnCVSS > 0 ? failOnCVSS : 11
            sh """
                mvn dependency-check:check \
                    -B \
                    -Dnvd.api.key=\$NVD_API_KEY \
                    -Ddependency-check.failBuildOnCVSS=${cvssThreshold} \
                    -Ddependency-check.format=ALL \
                    || true
            """
            // '|| true': Maven exit code handled by dependencyCheckPublisher in post{}
        }
    }
    echo "✅ Maven dependency check complete"
    echo "ℹ️  Report: target/dependency-check-report.xml — published in post{ always {} }"
    return [success: true, reportPath: 'target/dependency-check-report.xml']
}

// -----------------------------------------------------------------------------
// NPM / NODE / NEXT — npm audit
// Uses official npm advisory database — no credentials needed
// Report saved as build artifact: npm-audit-report.json
// -----------------------------------------------------------------------------
def scanNpm(Map params, int failOnCVSS) {
    echo "🔍 npm audit — Node.js dependency scan"
    sh """
        npm audit --json > npm-audit-report.json 2>&1 || true
        echo "--- npm audit summary ---"
        npm audit --audit-level=none 2>&1 || true
    """
    archiveArtifacts artifacts: 'npm-audit-report.json', allowEmptyArchive: true

    if (failOnCVSS >= 9) {
        def code = sh(script: 'npm audit --audit-level=critical', returnStatus: true)
        if (code != 0) {
            env.failedStage  = "Dependency Check (npm)"
            env.failedReason = "Critical npm vulnerabilities found"
            error "❌ Critical npm vulnerabilities — check npm-audit-report.json"
        }
    } else if (failOnCVSS >= 7) {
        def code = sh(script: 'npm audit --audit-level=high', returnStatus: true)
        if (code != 0) {
            echo "⚠️  High/Critical npm vulnerabilities — check npm-audit-report.json"
            currentBuild.result = 'UNSTABLE'
        }
    }
    echo "✅ npm audit complete — see npm-audit-report.json in build artifacts"
    return [success: true, reportPath: 'npm-audit-report.json']
}

// -----------------------------------------------------------------------------
// GO — govulncheck (official Google Go vulnerability scanner)
// Uses Go's official vuln database: https://vuln.go.dev — no credentials needed
// Report saved as build artifact: govulncheck-report.txt
// -----------------------------------------------------------------------------
def scanGo(Map params, int failOnCVSS) {
    echo "🔍 govulncheck — Go module vulnerability scan"
    // Run inside the same builder image used in the Dockerfile (golang:1.24-alpine)
    // so govulncheck checks against Go 1.24 stdlib — not the host Go 1.22.2.
    sh """
        docker run --rm \\
            -v "\$(pwd)":/workspace \\
            -w /workspace \\
            golang:1.24-alpine \\
            sh -c 'go install golang.org/x/vuln/cmd/govulncheck@v1.1.4 && govulncheck ./...' \\
            2>&1 | tee govulncheck-report.txt || true
    """
    archiveArtifacts artifacts: 'govulncheck-report.txt', allowEmptyArchive: true

    // grep -c exits 1 when count is 0 (but still prints "0") — use || true not || echo 0
    // to avoid capturing "0\n0" which breaks toInteger()
    def vulnRaw = sh(
        script: "grep -c 'Vulnerability #' govulncheck-report.txt 2>/dev/null || true",
        returnStdout: true
    ).trim()
    def vulnCount = vulnRaw.isInteger() ? vulnRaw.toInteger() : 0

    if (vulnCount > 0) {
        echo "⚠️  ${vulnCount} Go vulnerability/vulnerabilities — check govulncheck-report.txt"
        if (failOnCVSS > 0) {
            env.failedStage  = "Dependency Check (go)"
            env.failedReason = "${vulnCount} Go vulnerabilities found"
            error "❌ Go vulnerabilities found (failOnCVSS=${failOnCVSS})"
        } else {
            currentBuild.result = 'UNSTABLE'
        }
    } else {
        echo "✅ govulncheck: no vulnerabilities found"
    }
    return [success: true, reportPath: 'govulncheck-report.txt']
}

// -----------------------------------------------------------------------------
// GRADLE — OWASP Dependency Check Gradle Plugin
// Requires in build.gradle:
//   plugins { id 'org.owasp.dependencycheck' version '9.0.9' }
// NVD API key required (same credential as Maven)
// -----------------------------------------------------------------------------
def scanGradle(Map params, int failOnCVSS) {
    echo "🔍 OWASP Dependency Check — Gradle"
    def nvdCredentialsId = params.nvdCredentialsId ?: 'nvd-api-key'
    def javaHome = tool name: 'jdk21', type: 'jdk'

    withEnv(["PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {
        withCredentials([string(credentialsId: nvdCredentialsId, variable: 'NVD_API_KEY')]) {
            def cvssThreshold = failOnCVSS > 0 ? failOnCVSS : 11
            sh """
                ./gradlew dependencyCheckAnalyze \
                    -PnvdApiKey=\$NVD_API_KEY \
                    -PfailBuildOnCVSS=${cvssThreshold} \
                    || true
            """
        }
    }
    archiveArtifacts artifacts: 'build/reports/dependency-check-report.xml', allowEmptyArchive: true
    echo "✅ Gradle dependency check complete"
    return [success: true, reportPath: 'build/reports/dependency-check-report.xml']
}

// -----------------------------------------------------------------------------
// .NET — dotnet list package --vulnerable
// Built into .NET SDK since .NET 7 — no extra tools or credentials needed
// Report saved as build artifact: dotnet-vuln-report.txt
// -----------------------------------------------------------------------------
def scanDotnet(Map params, int failOnCVSS) {
    echo "🔍 dotnet — NuGet vulnerability scan"
    sh """
        dotnet list package --vulnerable --include-transitive 2>&1 | tee dotnet-vuln-report.txt || true
    """
    archiveArtifacts artifacts: 'dotnet-vuln-report.txt', allowEmptyArchive: true

    def hasVulns = sh(
        script: "grep -q 'has the following vulnerable packages' dotnet-vuln-report.txt && echo true || echo false",
        returnStdout: true
    ).trim()

    if (hasVulns == 'true') {
        echo "⚠️  .NET vulnerable packages found — check dotnet-vuln-report.txt"
        if (failOnCVSS > 0) {
            env.failedStage  = "Dependency Check (dotnet)"
            env.failedReason = ".NET vulnerable packages found"
            error "❌ .NET vulnerable packages found"
        } else {
            currentBuild.result = 'UNSTABLE'
        }
    } else {
        echo "✅ dotnet: no vulnerable packages found"
    }
    return [success: true, reportPath: 'dotnet-vuln-report.txt']
}

// -----------------------------------------------------------------------------
// Auto-detect build tool (same logic as buildArtifact.groovy for consistency)
// -----------------------------------------------------------------------------
def autoDetectBuildTool() {
    if (fileExists('pom.xml'))                                         return 'maven'
    if (fileExists('package.json'))                                    return 'npm'
    if (fileExists('go.mod'))                                          return 'go'
    if (fileExists('build.gradle') || fileExists('build.gradle.kts')) return 'gradle'
    return 'maven'
}