/**
 * osvScanner.groovy
 *
 * Runs Google OSV-Scanner against your project dependencies.
 * Complements existing scanners — fills gaps especially for Python projects.
 *
 * WHAT IT SCANS (auto-detected from project files):
 *   Python  → requirements.txt, requirements*.txt, Pipfile.lock, poetry.lock
 *   Java    → pom.xml (second opinion alongside OWASP)
 *   Node    → package-lock.json (second opinion alongside npm audit)
 *   Go      → go.sum (redundant with govulncheck but harmless)
 *   .NET    → packages.lock.json
 *
 * WHY OSV-SCANNER FOR PYTHON?
 *   Python has no built-in CVE scanner in your current pipeline.
 *   OSV-Scanner uses Google's OSV.dev database which is:
 *     - Updated faster than NVD
 *     - Covers PyPI, npm, Maven, Go, NuGet in one tool
 *     - No credentials or API keys needed
 *     - Runs via Docker — no installation required
 *
 * GENERATES:
 *   osv-report.json → uploaded to DefectDojo as "OSV Scan"
 *   osv-report.txt  → human readable, archived as Jenkins build artifact
 *
 * JENKINS SETUP: None — runs via Docker, no plugins or credentials needed
 *
 * USAGE IN JENKINSFILE:
 *   stage('OSV Scan') {
 *       steps { script { osvScanner() } }
 *   }
 *
 * OR call alongside owaspDependencyCheck:
 *   stage('Dependency Check') {
 *       steps {
 *           script {
 *               owaspDependencyCheck(failOnCVSS: 0)
 *               osvScanner()   // ← add this line
 *           }
 *       }
 *   }
 *
 * failOnCritical: false (default) = report only, never blocks pipeline
 * failOnCritical: true            = fails build if CRITICAL CVEs found
 */
def call(Map params = [:]) {

    def failOnCritical = params.containsKey('failOnCritical') ? params.failOnCritical : false
    def lockfileOnly   = params.containsKey('lockfileOnly')   ? params.lockfileOnly   : false

    echo "=== OSV-Scanner — Dependency Vulnerability Scan ==="
    echo "Fail on Critical: ${failOnCritical}"

    // Detect what manifest files exist in the workspace
    def manifestFiles = detectManifestFiles()

    if (!manifestFiles) {
        echo "ℹ️  No supported manifest files found — skipping OSV scan"
        echo "ℹ️  Expected: requirements.txt, pom.xml, package-lock.json, go.sum, etc."
        return [success: true, skipped: true]
    }

    echo "📦 Scanning manifest files: ${manifestFiles.join(', ')}"

    try {
        // ------------------------------------------------------------------
        // Run OSV-Scanner via Docker
        // No installation needed — pulls the image on first run
        // ghcr.io/google/osv-scanner is the official Google image
        //
        // --recursive: scan all manifest files found in the workspace
        // --format json: machine readable for DefectDojo upload
        // --output: write to file (exit code 1 if vulns found, handled below)
        // ------------------------------------------------------------------
        def exitCode = sh(
            script: """
                # Run OSV-Scanner and save JSON report for DefectDojo
                docker run --rm \\
                    -v "\$(pwd)":/workspace \\
                    ghcr.io/google/osv-scanner:latest \\
                    scan \\
                    --recursive \\
                    --format json \\
                    --output /workspace/osv-report.json \\
                    /workspace 2>/dev/null || true

                # Also generate human-readable text report for Jenkins artifact
                docker run --rm \\
                    -v "\$(pwd)":/workspace \\
                    ghcr.io/google/osv-scanner:latest \\
                    scan \\
                    --recursive \\
                    --format table \\
                    --output /workspace/osv-report.txt \\
                    /workspace 2>&1 || true

                echo "OSV-Scanner complete"
            """,
            returnStatus: true
        )

        // Archive human-readable report as Jenkins build artifact
        if (fileExists('osv-report.txt')) {
            archiveArtifacts artifacts: 'osv-report.txt', allowEmptyArchive: true
        }

        // Parse results from JSON report
        if (fileExists('osv-report.json')) {
            def reportText   = readFile('osv-report.json')
            def report       = readJSON text: reportText
            def vulnCount    = 0
            def criticalCount = 0

            // Count vulnerabilities across all results
            report.results?.each { result ->
                result.packages?.each { pkg ->
                    pkg.vulnerabilities?.each { vuln ->
                        vulnCount++
                        // Check severity from database-specific fields
                        vuln.database_specific?.severity?.each { sev ->
                            if (sev == 'CRITICAL') criticalCount++
                        }
                    }
                }
            }

            if (vulnCount > 0) {
                echo "⚠️  OSV-Scanner found ${vulnCount} vulnerabilities (${criticalCount} CRITICAL)"
                echo "ℹ️  Check osv-report.txt in build artifacts for details"
                echo "ℹ️  Full report uploaded to DefectDojo via publishToDefectDojo()"

                if (failOnCritical && criticalCount > 0) {
                    env.failedStage  = "OSV-Scanner"
                    env.failedReason = "OSV-Scanner found ${criticalCount} CRITICAL vulnerabilities"
                    error "❌ OSV-Scanner: ${criticalCount} CRITICAL vulnerabilities found"
                } else if (vulnCount > 0) {
                    currentBuild.result = 'UNSTABLE'
                }
            } else {
                echo "✅ OSV-Scanner: no vulnerabilities found"
            }

            return [success: true, vulnCount: vulnCount, criticalCount: criticalCount, reportPath: 'osv-report.json']

        } else {
            echo "⚠️  OSV report file not generated — check Docker access"
            return [success: false, error: 'Report file not generated']
        }

    } catch (Exception e) {
        env.failedStage  = "OSV-Scanner"
        env.failedReason = e.getMessage()
        echo "❌ OSV-Scanner failed: ${e.getMessage()}"
        // Never block pipeline for OSV failures — it is supplementary
        currentBuild.result = 'UNSTABLE'
        return [success: false, error: e.getMessage()]
    }
}

// -----------------------------------------------------------------------------
// Detect which manifest files exist in the workspace
// Returns list of detected file names for logging
// OSV-Scanner finds them automatically via --recursive but we log what it found
// -----------------------------------------------------------------------------
def detectManifestFiles() {
    def manifests = []

    // Python
    if (fileExists('requirements.txt'))     manifests << 'requirements.txt'
    if (fileExists('Pipfile.lock'))         manifests << 'Pipfile.lock'
    if (fileExists('poetry.lock'))          manifests << 'poetry.lock'

    // Java
    if (fileExists('pom.xml'))              manifests << 'pom.xml'
    if (fileExists('build.gradle'))         manifests << 'build.gradle'

    // Node.js
    if (fileExists('package-lock.json'))    manifests << 'package-lock.json'
    if (fileExists('yarn.lock'))            manifests << 'yarn.lock'

    // Go
    if (fileExists('go.sum'))               manifests << 'go.sum'

    // .NET
    if (fileExists('packages.lock.json'))   manifests << 'packages.lock.json'

    return manifests
}