/**
 * publishToDefectDojo.groovy
 *
 * Uploads security scan results from ALL scanners to DefectDojo.
 *
 * CALLED IN post { always {} } — runs regardless of which stage failed.
 * This ensures security results always reach DefectDojo even if k8s update
 * or any other stage fails.
 *
 * WHAT IT UPLOADS (only files that exist — safe on any project type):
 *   trivy-report.json                    → Trivy image scan       (vulnScanApplicationImage)
 *   trivy-base-report.json               → Trivy base image scan  (vulnScanDocker)
 *   target/dependency-check-report.xml   → OWASP Maven/Gradle     (owaspDependencyCheck)
 *   npm-audit-report.json                → npm audit              (owaspDependencyCheck)
 *   gitleaks-report.json                 → Gitleaks secrets       (vulnScanDocker)
 *   govulncheck-report.json              → Go vulncheck           (owaspDependencyCheck)
 *   dotnet-vuln-report.json              → .NET vulnerabilities   (owaspDependencyCheck)
 *
 * DefectDojo deduplicates findings:
 *   Same CVE found again = updates existing ticket, not a duplicate
 *   Fixed CVEs automatically closed on next build (close_old_findings=true)
 *
 * JENKINS SETUP (one time):
 *   Manage Jenkins → Credentials → Add → Secret text
 *   ID:    defectdojo-api-token
 *   Value: DefectDojo → click username top right → API v2 → copy token
 *
 * DEFECTDOJO SETUP (one time per service):
 *   Create a Product for each service, then an Engagement inside it.
 *   Copy the Engagement ID from the URL bar.
 *
 * USAGE IN JENKINSFILE — add to environment{} block:
 *   DEFECTDOJO_URL           = 'https://defectdojo.devops.softnethq.co.tz'
 *   DEFECTDOJO_ENGAGEMENT_ID = '3'   // unique per service — get from DefectDojo URL
 *
 * USAGE — in post { always {} } (recommended):
 *   post {
 *       always {
 *           script {
 *               dependencyCheckPublisher(...)  // Maven report in Jenkins UI
 *               publishToDefectDojo()          // all scanner results to DefectDojo
 *           }
 *       }
 *   }
 */
def call(Map params = [:]) {

    def defectDojoUrl     = params.defectDojoUrl     ?: env.DEFECTDOJO_URL           ?: ''
    def engagementId      = params.engagementId      ?: env.DEFECTDOJO_ENGAGEMENT_ID ?: ''
    def credentialsId     = params.credentialsId     ?: 'defectdojo-api-token'
    def branchName        = params.branchName        ?: env.BRANCH_NAME              ?: 'main'
    def buildNumber       = params.buildNumber       ?: env.BUILD_NUMBER             ?: '0'
    def notificationEmail = params.notificationEmail ?: env.NOTIFICATION_EMAIL       ?: ''

    // Guard: skip silently if not configured — does not fail the build
    if (!defectDojoUrl || !engagementId) {
        echo "ℹ️  DefectDojo not configured — skipping upload"
        echo "ℹ️  Add DEFECTDOJO_URL and DEFECTDOJO_ENGAGEMENT_ID to Jenkinsfile environment{}"
        return [success: false, skipped: true]
    }

    echo "=== Publishing Security Results to DefectDojo ==="
    echo "DefectDojo:  ${defectDojoUrl}"
    echo "Engagement:  ${engagementId}"
    echo "Build:       #${buildNumber}"

    // -------------------------------------------------------------------------
    // Scan report files mapped to DefectDojo scan types
    // Add new scanners here as they are added to the pipeline
    //
    // DefectDojo scan types reference:
    //   https://defectdojo.github.io/django-DefectDojo/integrations/parsers/
    // -------------------------------------------------------------------------
    def scansToUpload = [
        // Trivy — container image CVE scans
        [file: 'trivy-report.json',                        type: 'Trivy Scan',            label: 'Trivy Image Scan'],
        [file: 'trivy-base-report.json',                   type: 'Trivy Scan',            label: 'Trivy Base Image Scan'],

        // OWASP / Maven / Gradle — Java dependency CVE scan
        [file: 'target/dependency-check-report.xml',       type: 'Dependency Check Scan', label: 'OWASP Dependency Check (Maven)'],
        [file: 'build/reports/dependency-check-report.xml',type: 'Dependency Check Scan', label: 'OWASP Dependency Check (Gradle)'],

        // npm audit — Node.js/React/Next.js dependency CVE scan
        [file: 'npm-audit-report.json',                    type: 'NPM Audit Scan',        label: 'npm Audit'],

        // Gitleaks — hardcoded secrets detection
        [file: 'gitleaks-report.json',                     type: 'Gitleaks Scan',         label: 'Gitleaks Secrets'],

        // govulncheck — Go module CVE scan (JSON from owaspDependencyCheck scanGo)
        [file: 'govulncheck-report.json',                  type: 'Govulncheck Scanner',   label: 'Go vulncheck'],

        // dotnet — .NET NuGet CVE scan (JSON from owaspDependencyCheck scanDotnet)
        [file: 'dotnet-vuln-report.json',                  type: 'Anchore Grype',         label: '.NET Vulnerabilities'],

        // OSV-Scanner — Google OSV.dev database scan (all languages, especially Python)
        [file: 'osv-report.json',                          type: 'OSV Scan',              label: 'OSV-Scanner'],
    ]

    def uploadedScans = []
    def failedUploads = []
    def skippedScans  = []

    withCredentials([string(credentialsId: credentialsId, variable: 'DD_API_TOKEN')]) {

        scansToUpload.each { scan ->
            // Skip files that don't exist — normal for language-specific reports
            if (!fileExists(scan.file)) {
                skippedScans << scan.label
                return
            }

            echo "📤 Uploading ${scan.label}..."

            def exitCode = sh(
                script: """
                    curl -sf -X POST \\
                        "${defectDojoUrl}/api/v2/import-scan/" \\
                        -H "Authorization: Token \$DD_API_TOKEN" \\
                        -F "scan_type=${scan.type}" \\
                        -F "file=@${scan.file}" \\
                        -F "engagement=${engagementId}" \\
                        -F "verified=false" \\
                        -F "active=true" \\
                        -F "close_old_findings=true" \\
                        -F "push_to_jira=false" \\
                        -F "version=${buildNumber}" \\
                        -F "branch_tag=${branchName}" \\
                        -o /dev/null \\
                        -w "%{http_code}" | grep -q "201"
                """,
                returnStatus: true
            )

            if (exitCode == 0) {
                echo "✅ ${scan.label} uploaded"
                uploadedScans << scan.label
            } else {
                echo "⚠️  ${scan.label} upload failed"
                failedUploads << scan.label
            }
        }
    }

    echo ""
    echo "=== DefectDojo Upload Summary ==="
    echo "✅ Uploaded: ${uploadedScans ?: 'none'}"
    echo "ℹ️  Skipped: ${skippedScans  ?: 'none'} (not produced by this project type — normal)"
    if (failedUploads) {
        echo "⚠️  Failed:  ${failedUploads} — check DefectDojo URL and API token"
    }

    // Send email only when at least one scan was uploaded
    // No email on clean run with no uploads (e.g. build failed before any scan ran)
    if (uploadedScans && notificationEmail) {
        sendDefectDojoEmail(
            defectDojoUrl:     defectDojoUrl,
            engagementId:      engagementId,
            uploadedScans:     uploadedScans,
            failedUploads:     failedUploads,
            notificationEmail: notificationEmail
        )
    }

    return [
        success:  true,
        uploaded: uploadedScans,
        failed:   failedUploads,
        skipped:  skippedScans
    ]
}

// -----------------------------------------------------------------------------
// Sends notification email with DefectDojo results link
// Only called when at least one scan was uploaded successfully
// -----------------------------------------------------------------------------
def sendDefectDojoEmail(Map params) {
    def defectDojoUrl     = params.defectDojoUrl
    def engagementId      = params.engagementId
    def uploadedScans     = params.uploadedScans ?: []
    def failedUploads     = params.failedUploads ?: []
    def notificationEmail = params.notificationEmail

    try {
        def scanRows = uploadedScans.collect { scan ->
            "<tr>" +
            "<td style='padding:8px 12px;border-bottom:1px solid #eee'>✅ ${scan}</td>" +
            "<td style='padding:8px 12px;border-bottom:1px solid #eee;color:#2e7d32;font-weight:bold'>Uploaded</td>" +
            "</tr>"
        }.join('')

        def failRows = failedUploads.collect { scan ->
            "<tr>" +
            "<td style='padding:8px 12px;border-bottom:1px solid #eee'>⚠️ ${scan}</td>" +
            "<td style='padding:8px 12px;border-bottom:1px solid #eee;color:#dc3545'>Failed</td>" +
            "</tr>"
        }.join('')

        emailext(
            subject: "🛡️ Security Results Ready — ${env.JOB_NAME} #${env.BUILD_NUMBER}",
            body: """<!DOCTYPE html>
<html><head>
<style>
  body{font-family:'Segoe UI',Arial,sans-serif;background:#f4f6f9;margin:0;padding:20px}
  .box{max-width:620px;margin:auto;background:white;border-radius:12px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.1)}
  .hdr{background:linear-gradient(135deg,#0d1b2a,#1b4332);color:white;padding:30px;text-align:center}
  .body{padding:25px 30px}
  .btn{display:inline-block;background:#1a73e8;color:white;padding:13px 30px;text-decoration:none;border-radius:8px;font-weight:bold;font-size:15px}
  .tip{background:#e8f0fe;border-left:4px solid #1a73e8;border-radius:6px;padding:15px 18px;margin:20px 0;font-size:13px;line-height:1.7}
  table{width:100%;border-collapse:collapse;font-size:14px}
  th{background:#f1f3f4;padding:9px 12px;text-align:left;font-size:11px;text-transform:uppercase;letter-spacing:.5px;color:#555}
  .ftr{background:#f8f9ff;padding:14px 30px;text-align:center;font-size:12px;color:#666;border-top:1px solid #eee}
</style>
</head><body>
<div class="box">
  <div class="hdr">
    <h2 style="margin:0 0 6px">🛡️ Security Results Published</h2>
    <p style="margin:0;opacity:.8;font-size:14px">${env.JOB_NAME} &mdash; Build #${env.BUILD_NUMBER}</p>
  </div>
  <div class="body">
    <p>Hello <strong>Team</strong>,</p>
    <p>Security scan results for <strong>${env.JOB_NAME} #${env.BUILD_NUMBER}</strong> are ready in DefectDojo. This is your central security dashboard — same idea as SonarQube but for vulnerabilities and secrets.</p>

    <div style="text-align:center;margin:28px 0">
      <a href="${defectDojoUrl}/engagement/${engagementId}" class="btn">
        🛡️ &nbsp; View Security Results in DefectDojo
      </a>
    </div>

    <h3 style="color:#0d1b2a;margin:22px 0 10px;font-size:15px">Scans Uploaded This Build</h3>
    <table>
      <tr><th>Scanner</th><th>Status</th></tr>
      ${scanRows}${failRows}
    </table>

    <div class="tip">
      <strong>What to do when you see findings:</strong><br>
      &bull; <strong>New CVE:</strong> check the fix version — update your dependency or base image<br>
      &bull; <strong>False positive:</strong> click finding → mark as False Positive → suppressed forever<br>
      &bull; <strong>Accepted risk:</strong> click → Risk Accepted → add justification and expiry date<br>
      &bull; <strong>Fixed:</strong> findings auto-close on the next build when vulnerability is gone<br>
      &bull; <strong>Metrics tab:</strong> shows your service security score trend over time
    </div>

    <p style="font-size:13px;color:#555;text-align:center">
      <a href="${defectDojoUrl}" style="color:#1a73e8">DefectDojo Dashboard</a>
      &nbsp;&bull;&nbsp;
      <a href="${env.BUILD_URL}" style="color:#1a73e8">Jenkins Build #${env.BUILD_NUMBER}</a>
    </p>
  </div>
  <div class="ftr">${defectDojoUrl} &nbsp;&bull;&nbsp; Softnet DevSecOps</div>
</div>
</body></html>""",
            to: notificationEmail,
            mimeType: 'text/html'
        )
        echo "✅ DefectDojo notification sent to: ${notificationEmail}"
    } catch (Exception e) {
        // Never fail the pipeline because of email — results are already uploaded
        echo "⚠️  DefectDojo email failed (results still uploaded): ${e.getMessage()}"
    }
}