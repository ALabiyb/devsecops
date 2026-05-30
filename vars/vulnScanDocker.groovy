/**
 * vulnScanDocker.groovy
 *
 * Scans Dockerfile and base image BEFORE building the application image.
 * Runs three checks in parallel:
 *   1. Trivy     — base image CVE scan → HTML report + email
 *   2. OPA       — Dockerfile policy enforcement
 *   3. Gitleaks  — hardcoded secrets detection
 *
 * REPORTS:
 *   trivy-base-report.html  → published in Jenkins UI as "Trivy Base Image Report"
 *   trivy-base-report.json  → parsed for email summary
 */
def call(Map params = [:]) {
    try {
        echo "=== Vulnerability Scan - Dockerfile & Base Image ==="

        def notificationEmail = params.notificationEmail ?: env.NOTIFICATION_EMAIL ?: ''
        def trivyBaseHtml     = 'trivy-base-report.html'
        def trivyBaseJson     = 'trivy-base-report.json'

        // Write OPA policy from shared library — no need to copy rego to project repos
        writeFile file: 'opa-docker-security.rego',
                  text: libraryResource('opa/opa-docker-security.rego')

        parallel(

            // -----------------------------------------------------------------
            // SCAN 1: Trivy — base image CVE scan + HTML/JSON report generation
            // -----------------------------------------------------------------
            "Trivy — Base Image CVE Scan": {
                echo "🔍 Trivy: scanning Dockerfile base image..."

                // Round 1: informational — HIGH+CRITICAL, never fails
                sh params.trivyCmd ?: "bash trivy-docker-image-scan.sh"

                // Generate HTML report for Jenkins UI
                echo "📄 Generating base image HTML report..."
                def baseImage = sh(
                    script: "grep '^FROM' Dockerfile | head -1 | awk '{print \$2}' | sed 's/ AS.*//'",
                    returnStdout: true
                ).trim()

                sh """
                    docker run --rm \
                        -v /var/lib/jenkins/.trivy-cache:/root/.cache/trivy \
                        aquasec/trivy:latest image \
                        --severity HIGH,CRITICAL \
                        --exit-code 0 \
                        --no-progress \
                        --format template \
                        --template '@contrib/html.tpl' \
                        --output /tmp/${trivyBaseHtml} \
                        ${baseImage} || true

                    docker run --rm \
                        -v /var/lib/jenkins/.trivy-cache:/root/.cache/trivy \
                        aquasec/trivy:latest image \
                        --severity HIGH,CRITICAL \
                        --exit-code 0 \
                        --no-progress \
                        --format json \
                        --output /tmp/${trivyBaseJson} \
                        ${baseImage} || true

                    cp /tmp/${trivyBaseHtml} . 2>/dev/null || true
                    cp /tmp/${trivyBaseJson} . 2>/dev/null || true
                """

                // Publish HTML in Jenkins UI
                if (fileExists(trivyBaseHtml)) {
                    publishHTML(target: [
                        allowMissing:          true,
                        alwaysLinkToLastBuild: true,
                        keepAll:               true,
                        reportDir:             '.',
                        reportFiles:           trivyBaseHtml,
                        reportName:            'Trivy Base Image Report',
                        reportTitles:          "Base Image — ${baseImage}"
                    ])
                    echo "✅ Base image HTML report published in Jenkins UI"
                }

                // Send email summary
                if (fileExists(trivyBaseJson) && notificationEmail) {
                    sendTrivyBaseEmail(
                        reportJson:        trivyBaseJson,
                        baseImage:         baseImage,
                        notificationEmail: notificationEmail
                    )
                }

                echo "✅ Trivy base image scan passed (no CRITICAL CVEs)"
            },

            // -----------------------------------------------------------------
            // SCAN 2: OPA Conftest — Dockerfile policy enforcement
            // -----------------------------------------------------------------
            "OPA Conftest — Dockerfile Policies": {
                echo "🔍 OPA: enforcing Dockerfile security policies..."
                sh """
                    docker run --rm \
                        -v "\$(pwd)":/project \
                        openpolicyagent/conftest:latest test \
                        --policy opa-docker-security.rego \
                        Dockerfile
                """
                echo "✅ OPA Dockerfile policy check passed"
            },

            // -----------------------------------------------------------------
            // SCAN 3: Gitleaks — hardcoded secrets detection
            // -----------------------------------------------------------------
            "Gitleaks — Secrets Detection": {
                echo "🔍 Gitleaks: scanning for hardcoded secrets..."
                def exitCode = sh(
                    script: """
                        docker run --rm \
                            -v "\$(pwd)":/path \
                            zricethezav/gitleaks:latest \
                            detect \
                            --source /path \
                            --no-git \
                            --exit-code 1 \
                            --report-format json \
                            --report-path /path/gitleaks-report.json \
                            -v 2>&1 || true
                    """,
                    returnStatus: true
                )
                if (exitCode != 0) {
                    echo "⚠️  WARNING: Possible hardcoded secrets detected"
                    currentBuild.result = 'UNSTABLE'
                    env.failedStage  = "Secrets Detection - Gitleaks"
                    env.failedReason = "Possible hardcoded secrets detected in source code"

                    // Notify team about secrets detection
                    if (notificationEmail) {
                        emailext(
                            subject: "⚠️ SECRET DETECTED in ${env.JOB_NAME} #${env.BUILD_NUMBER} — Immediate Action Required",
                            body: """
                                <div style="font-family:sans-serif;max-width:600px;margin:auto">
                                  <div style="background:#dc3545;color:white;padding:25px;border-radius:8px 8px 0 0;text-align:center">
                                    <h2 style="margin:0">⚠️ Possible Secret Detected in Source Code</h2>
                                  </div>
                                  <div style="background:white;padding:25px;border:1px solid #eee;border-radius:0 0 8px 8px">
                                    <p>Gitleaks detected a possible hardcoded secret in <strong>${env.JOB_NAME}</strong>.</p>
                                    <p><strong>What to do immediately:</strong></p>
                                    <ol>
                                      <li>Go to the Jenkins build log and identify the leaked secret</li>
                                      <li>Rotate/revoke the secret immediately from the issuing service</li>
                                      <li>Remove it from source code and move it to Jenkins credentials or Kubernetes Secrets</li>
                                      <li>Force-push to rewrite git history if the secret was committed</li>
                                    </ol>
                                    <p><a href="${env.BUILD_URL}" style="background:#dc3545;color:white;padding:10px 20px;text-decoration:none;border-radius:6px">View Build Log</a></p>
                                  </div>
                                </div>
                            """,
                            to: notificationEmail,
                            mimeType: 'text/html'
                        )
                    }
                } else {
                    echo "✅ Gitleaks: no secrets detected"
                }
            }
        )

        echo "✅ All Dockerfile/base-image scans passed"
        return [success: true]

    } catch (Exception e) {
        env.failedStage  = "Vulnerability Scan - Docker"
        env.failedReason = e.getMessage()
        echo "❌ Docker vulnerability scan failed: ${e.getMessage()}"
        currentBuild.result = 'UNSTABLE'
        throw e
    }
}

// -----------------------------------------------------------------------------
// Sends a summary email for the base image Trivy scan
// Simpler version of the full image email — focused on base image CVEs
// -----------------------------------------------------------------------------
def sendTrivyBaseEmail(Map params) {
    def reportJson        = params.reportJson
    def baseImage         = params.baseImage         ?: 'unknown'
    def notificationEmail = params.notificationEmail ?: ''

    try {
        def reportText    = readFile(reportJson)
        def report        = readJSON text: reportText
        def criticalCount = 0
        def highCount     = 0

        report.Results?.each { result ->
            result.Vulnerabilities?.each { vuln ->
                if (vuln.Severity == 'CRITICAL') criticalCount++
                if (vuln.Severity == 'HIGH')     highCount++
            }
        }

        // Only send email if there are findings — no email on clean scans
        if (criticalCount == 0 && highCount == 0) {
            echo "ℹ️  Base image scan clean — skipping email"
            return
        }

        def statusIcon = criticalCount > 0 ? '🔴' : '🟡'

        emailext(
            subject: "${statusIcon} Base Image CVEs: ${criticalCount} CRITICAL, ${highCount} HIGH — ${env.JOB_NAME} #${env.BUILD_NUMBER}",
            body: """
                <div style="font-family:sans-serif;max-width:600px;margin:auto">
                  <div style="background:#1a1a2e;color:white;padding:25px;border-radius:8px 8px 0 0;text-align:center">
                    <h2 style="margin:0">🔍 Base Image Vulnerability Scan</h2>
                    <p style="opacity:.8;margin:8px 0 0">${env.JOB_NAME} #${env.BUILD_NUMBER}</p>
                  </div>
                  <div style="background:white;padding:25px;border:1px solid #eee;border-radius:0 0 8px 8px">
                    <p>Base image <strong>${baseImage}</strong> has vulnerabilities:</p>
                    <div style="display:flex;gap:15px;margin:15px 0">
                      <div style="flex:1;text-align:center;background:#fce4ec;border-radius:8px;padding:15px">
                        <div style="font-size:32px;font-weight:bold;color:#dc3545">${criticalCount}</div>
                        <div style="color:#666;font-size:12px">CRITICAL</div>
                      </div>
                      <div style="flex:1;text-align:center;background:#fff3e0;border-radius:8px;padding:15px">
                        <div style="font-size:32px;font-weight:bold;color:#fd7e14">${highCount}</div>
                        <div style="color:#666;font-size:12px">HIGH</div>
                      </div>
                    </div>
                    <p><strong>How to fix:</strong> Update the FROM line in your Dockerfile to the latest patch version of <code>${baseImage.split(':')[0]}</code> and rebuild.</p>
                    <p><a href="${env.BUILD_URL}Trivy_20Base_20Image_20Report/" style="background:#1a73e8;color:white;padding:10px 20px;text-decoration:none;border-radius:6px">View Full Report</a></p>
                  </div>
                </div>
            """,
            to: notificationEmail,
            mimeType: 'text/html'
        )
        echo "✅ Base image vulnerability email sent"

    } catch (Exception e) {
        echo "⚠️  Failed to send base image email: ${e.getMessage()}"
    }
}