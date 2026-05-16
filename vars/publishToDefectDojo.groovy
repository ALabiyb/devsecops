/**
 * publishToDefectDojo.groovy
 *
 * Uploads security scan results from all scanners to DefectDojo.
 * Called as the last stage in every pipeline before post{}.
 *
 * WHAT IT UPLOADS:
 *   trivy-report.json              → Trivy image scan (from vulnScanApplicationImage)
 *   trivy-base-report.json         → Trivy base image scan (from vulnScanDocker)
 *   target/dependency-check-report.xml → OWASP (from owaspDependencyCheck, Maven)
 *   npm-audit-report.json          → npm audit (from owaspDependencyCheck, npm)
 *   gitleaks-report.json           → Gitleaks (from vulnScanDocker)
 *
 * It only uploads files that exist — safe to call on any project type.
 * DefectDojo deduplicates findings — same CVE found again = same ticket (not duplicate).
 * Fixed CVEs are automatically closed on the next build (close_old_findings=true).
 *
 * JENKINS SETUP (one time):
 *   Manage Jenkins → Credentials → Add → Secret text
 *   ID: defectdojo-api-token
 *   Value: get from DefectDojo → top right menu → API v2 → Authorize → copy token
 *
 * DEFECTDOJO SETUP (one time per service — see README):
 *   Create a Product per service, then an Engagement inside it.
 *   Copy the Engagement ID and add to Jenkinsfile environment{}.
 *
 * USAGE — add to Jenkinsfile environment{} block:
 *   DEFECTDOJO_URL           = 'http://192.168.15.85:8090'
 *   DEFECTDOJO_ENGAGEMENT_ID = '1'   // unique number per service
 *
 * USAGE — add as last stage before post{}:
 *   stage('Publish Security Results') {
 *       steps { script { publishToDefectDojo() } }
 *   }
 */
def call(Map params = [:]) {

    def defectDojoUrl     = params.defectDojoUrl     ?: env.DEFECTDOJO_URL           ?: 'http://192.168.15.85:8090'
    def engagementId      = params.engagementId      ?: env.DEFECTDOJO_ENGAGEMENT_ID ?: ''
    def credentialsId     = params.credentialsId     ?: 'defectdojo-api-token'
    def branchName        = params.branchName        ?: env.BRANCH_NAME              ?: 'main'
    def buildNumber       = params.buildNumber       ?: env.BUILD_NUMBER             ?: '0'
    def notificationEmail = params.notificationEmail ?: env.NOTIFICATION_EMAIL       ?: ''

    if (!engagementId) {
        echo "⚠️  DEFECTDOJO_ENGAGEMENT_ID not set in Jenkinsfile environment{} — skipping"
        echo "ℹ️  See publishToDefectDojo.groovy header for setup instructions"
        return [success: false, skipped: true]
    }

    echo "=== Publishing Security Results to DefectDojo ==="
    echo "DefectDojo:    ${defectDojoUrl}"
    echo "Engagement:    ${engagementId}"
    echo "Build:         ${buildNumber}"

    // Map of report files to DefectDojo scan types
    // Only files that exist will be uploaded
    def scansToUpload = [
        [file: 'trivy-report.json',                        type: 'Trivy Scan',             label: 'Trivy Image Scan'],
        [file: 'trivy-base-report.json',                   type: 'Trivy Scan',             label: 'Trivy Base Image Scan'],
        [file: 'target/dependency-check-report.xml',       type: 'Dependency Check Scan',  label: 'OWASP Dependency Check'],
        [file: 'npm-audit-report.json',                    type: 'NPM Audit Scan',         label: 'npm Audit'],
        [file: 'gitleaks-report.json',                     type: 'Gitleaks Scan',          label: 'Gitleaks Secrets'],
        [file: 'govulncheck-report.txt',                   type: 'Govulncheck Scanner',    label: 'Go vulncheck'],
    ]

    def uploadedScans = []
    def failedUploads = []
    def skippedScans  = []

    withCredentials([string(credentialsId: credentialsId, variable: 'DD_API_TOKEN')]) {

        scansToUpload.each { scan ->
            if (!fileExists(scan.file)) {
                skippedScans << scan.label
                return // not found — this project type doesn't produce this report
            }

            echo "📤 Uploading ${scan.label}..."

            def exitCode = sh(
                script: """
                    curl -sf -X POST \
                        "${defectDojoUrl}/api/v2/import-scan/" \
                        -H "Authorization: Token \$DD_API_TOKEN" \
                        -F "scan_type=${scan.type}" \
                        -F "file=@${scan.file}" \
                        -F "engagement=${engagementId}" \
                        -F "verified=false" \
                        -F "active=true" \
                        -F "close_old_findings=true" \
                        -F "push_to_jira=false" \
                        -F "version=${buildNumber}" \
                        -F "branch_tag=${branchName}" \
                        -o /dev/null \
                        -w "%{http_code}" | grep -q "201"
                """,
                returnStatus: true
            )

            if (exitCode == 0) {
                echo "✅ ${scan.label} uploaded successfully"
                uploadedScans << scan.label
            } else {
                echo "⚠️  ${scan.label} upload failed — check DefectDojo connectivity"
                failedUploads << scan.label
            }
        }
    }

    echo "✅ Uploaded:  ${uploadedScans  ?: 'none'}"
    echo "ℹ️  Skipped:  ${skippedScans   ?: 'none'} (report files not found — normal for this project type)"
    if (failedUploads) {
        echo "⚠️  Failed:   ${failedUploads} — verify DefectDojo is running and API token is correct"
    }

    // Send email with DefectDojo link
    if (uploadedScans && notificationEmail) {
        sendDefectDojoEmail(
            defectDojoUrl:     defectDojoUrl,
            engagementId:      engagementId,
            uploadedScans:     uploadedScans,
            failedUploads:     failedUploads,
            notificationEmail: notificationEmail
        )
    }

    return [success: true, uploaded: uploadedScans, failed: failedUploads, skipped: skippedScans]
}

// -----------------------------------------------------------------------------
// Email with link to DefectDojo results
// Only sent when at least one scan was uploaded successfully
// -----------------------------------------------------------------------------
def sendDefectDojoEmail(Map params) {
    def defectDojoUrl     = params.defectDojoUrl
    def engagementId      = params.engagementId
    def uploadedScans     = params.uploadedScans ?: []
    def failedUploads     = params.failedUploads ?: []
    def notificationEmail = params.notificationEmail

    try {
        def scanRows = uploadedScans.collect { scan ->
            "<tr><td style='padding:8px 12px;border-bottom:1px solid #eee'>✅ ${scan}</td>" +
            "<td style='padding:8px 12px;border-bottom:1px solid #eee;color:#2e7d32;font-weight:bold'>Uploaded</td></tr>"
        }.join('')

        def failRows = failedUploads.collect { scan ->
            "<tr><td style='padding:8px 12px;border-bottom:1px solid #eee'>⚠️ ${scan}</td>" +
            "<td style='padding:8px 12px;border-bottom:1px solid #eee;color:#dc3545'>Failed</td></tr>"
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
      &bull; <strong>New CVE:</strong> check the fix version in the finding — update your dependency or base image<br>
      &bull; <strong>False positive:</strong> click the finding → mark as False Positive → it won't appear again<br>
      &bull; <strong>Accepted risk:</strong> click → Risk Accepted → add a justification and expiry date<br>
      &bull; <strong>Fixed:</strong> findings automatically close on the next build when the vulnerability is gone<br>
      &bull; <strong>Metrics tab:</strong> shows your service security trend over time
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
        echo "⚠️  DefectDojo email failed (results still uploaded): ${e.getMessage()}"
    }
}