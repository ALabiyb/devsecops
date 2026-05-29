/**
 * dastScan.groovy
 *
 * Dynamic Application Security Testing using OWASP ZAP.
 * Scans the RUNNING application on the K8s cluster after deployment.
 *
 * STAGE ORDER:
 *   Stage 13: k8sManifestScanAndUpdate → deploys new image to K8s staging
 *   Stage 14: dastScan                 → ZAP scans the live staging URL  ← THIS
 *
 * Scan type: ZAP Baseline
 *   - Passive scan only — no active attacks (safe for staging)
 *   - Spiders the app and checks for common misconfigurations
 *   - Runs in ~2–5 minutes depending on app size
 *   - Reports: HTML (archived in Jenkins) + JSON (uploaded to DefectDojo)
 *
 * To use full active scan (slower, more thorough) set scanType: 'full'.
 * To use API scan with OpenAPI spec set scanType: 'api' and apiSpecUrl.
 *
 * Non-blocking: ZAP alert findings mark build UNSTABLE, not FAILURE.
 * Skipped silently if STAGING_URL is not set (safe for services without staging).
 *
 * Jenkins requirements:
 *   Docker available on Jenkins host (ZAP runs as container — no install needed)
 *   env.STAGING_URL set in Jenkinsfile environment block
 *   env.DEFECTDOJO_URL and env.DEFECTDOJO_ENGAGEMENT_ID set
 *   Credential 'defectdojo-api-token' (same token used by publishToDefectDojo)
 *
 * Network requirement:
 *   Jenkins host (192.168.200.78) must be able to reach the K8s staging URL.
 *   ZAP runs with --network host so it uses the Jenkins host's routing.
 */
def call(Map params = [:]) {
    def stagingUrl    = params.stagingUrl    ?: env.STAGING_URL
    def scanType      = params.scanType      ?: 'baseline'   // baseline | full | api
    def apiSpecUrl    = params.apiSpecUrl    ?: ''           // for scanType: 'api'
    def waitSeconds   = params.waitSeconds   ?: 90           // seconds to wait after K8s deploy
    def ddUrl         = params.ddUrl         ?: env.DEFECTDOJO_URL
    def engagementId  = params.engagementId  ?: env.DEFECTDOJO_ENGAGEMENT_ID
    def ddCredId      = params.ddCredId      ?: 'defectdojo-api-token'
    def zapImage      = 'ghcr.io/zaproxy/zaproxy:stable'
    def reportDir     = "${env.WORKSPACE}/zap-reports"

    if (!stagingUrl) {
        echo "ℹ️  STAGING_URL not set — skipping DAST scan (add to Jenkinsfile environment block)"
        return
    }

    try {
        echo "=== DAST Scan (OWASP ZAP ${scanType}) ==="
        echo "Target : ${stagingUrl}"
        echo "Reports: ${reportDir}"

        // Wait for K8s deployment to stabilise after manifest update
        if (waitSeconds > 0) {
            echo "⏳ Waiting ${waitSeconds}s for deployment to become ready..."
            sleep(time: waitSeconds, unit: 'SECONDS')
        }

        sh "mkdir -p '${reportDir}'"

        // Build the ZAP command based on scan type
        def zapCmd
        switch (scanType) {
            case 'full':
                zapCmd = """
                    zap-full-scan.py \
                      -t "${stagingUrl}" \
                      -r zap-report.html \
                      -J zap-report.json \
                      -I -l WARN
                """
                break
            case 'api':
                if (!apiSpecUrl) error("scanType 'api' requires apiSpecUrl parameter")
                zapCmd = """
                    zap-api-scan.py \
                      -t "${apiSpecUrl}" \
                      -f openapi \
                      -r zap-report.html \
                      -J zap-report.json \
                      -I -l WARN
                """
                break
            default: // baseline
                zapCmd = """
                    zap-baseline.py \
                      -t "${stagingUrl}" \
                      -r zap-report.html \
                      -J zap-report.json \
                      -I -l WARN
                """
        }

        // Run ZAP — returnStatus so non-zero exit (alerts found) doesn't throw
        def zapExit = sh(
            script: """
                docker run --rm \
                  --network host \
                  -v "${reportDir}:/zap/wrk:rw" \
                  -t ${zapImage} \
                  ${zapCmd}
            """,
            returnStatus: true
        )

        // Exit codes: 0=clean, 1=warnings, 2=failures, 3=error
        if (zapExit == 3) {
            error "ZAP failed to run (exit 3) — check network connectivity to ${stagingUrl}"
        } else if (zapExit == 2) {
            echo "⚠️  ZAP found FAIL-level alerts — marking UNSTABLE"
            currentBuild.result = 'UNSTABLE'
        } else if (zapExit == 1) {
            echo "⚠️  ZAP found WARN-level alerts — review report"
        } else {
            echo "✅ ZAP scan clean — no alerts found"
        }

        // Upload JSON report to DefectDojo
        def zapJson = "${reportDir}/zap-report.json"
        if (fileExists(zapJson)) {
            withCredentials([string(credentialsId: ddCredId, variable: 'DD_TOKEN')]) {
                def httpCode = sh(
                    script: """
                        curl -sf \
                          --connect-timeout 30 \
                          --max-time 120 \
                          -X POST \
                          -H "Authorization: Token \${DD_TOKEN}" \
                          -F "engagement=${engagementId}" \
                          -F "scan_type=ZAP Scan" \
                          -F "file=@${zapJson}" \
                          -F "active=true" \
                          -F "verified=false" \
                          -F "close_old_findings=true" \
                          "${ddUrl}/api/v2/import-scan/" \
                          -w "%{http_code}" -o /dev/null
                    """,
                    returnStdout: true
                ).trim()

                if (httpCode == '201') {
                    echo "✅ ZAP report uploaded to DefectDojo (HTTP ${httpCode})"
                } else {
                    echo "⚠️  DefectDojo upload returned HTTP ${httpCode} — check token and engagement ID"
                }
            }
        }

        // Archive HTML report as a Jenkins build artifact
        def zapHtml = "${reportDir}/zap-report.html"
        if (fileExists(zapHtml)) {
            sh "cp '${zapHtml}' '${env.WORKSPACE}/zap-report.html'"
            archiveArtifacts artifacts: 'zap-report.html', allowEmptyArchive: true
            echo "📄 ZAP HTML report archived — download from Jenkins build artifacts"
        }

    } catch (e) {
        echo "⚠️  DAST scan failed: ${e.message}"
        echo "Pipeline continues — check ZAP Docker image and network access to ${stagingUrl}"
        currentBuild.result = 'UNSTABLE'
    } finally {
        sh "rm -rf '${reportDir}'"
    }
}
