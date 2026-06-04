/**
 * productionApproval.groovy
 *
 * Production deploy gate: sends an approval email, pauses the pipeline, then
 * updates the K8s manifest with a release version tag on approval.
 *
 * Designed for a 'prod' branch workflow:
 *   - Developer pushes / merges to 'prod'
 *   - This function emails the team with an Approve / Reject button
 *   - If approved within the timeout, k8sManifestScanAndUpdate() runs using
 *     the releaseVersion (e.g. "1.2.0") as the image tag — NOT the build number
 *   - If rejected or timed out, the pipeline fails with a clear message
 *
 * USAGE IN JENKINSFILE:
 *   stage('Production Approval') {
 *       when { branch 'prod' }
 *       steps {
 *           script {
 *               productionApproval(
 *                   releaseVersion: '1.2.0',          // required — the tag to deploy
 *                   recipients:     env.NOTIFICATION_EMAIL,
 *                   timeoutMinutes: 30
 *               )
 *           }
 *       }
 *   }
 *
 * The releaseVersion becomes the image tag in the manifest, e.g.:
 *   harbor.devops.softnethq.co.tz/k8s_dashboard/k8s-dashboard:1.2.0
 *
 * All other manifest params (repo URL, paths, branch, etc.) are inherited
 * from the Jenkinsfile environment block — same as k8sManifestScanAndUpdate().
 *
 * PARAMETERS:
 *   releaseVersion   (required) semantic version or tag to stamp on the image
 *   recipients       email address(es) to notify — comma-separated for multiple
 *   timeoutMinutes   how long to wait before auto-abort (default: 30)
 *   approverMessage  custom message shown on the Jenkins input prompt
 *   skipManifest     set true to gate only (don't update manifest) — useful for testing
 */
def call(Map params = [:]) {

    def releaseVersion  = params.releaseVersion  ?: env.RELEASE_VERSION  ?: error("productionApproval: 'releaseVersion' is required")
    def recipients      = params.recipients      ?: env.NOTIFICATION_EMAIL ?: ''
    def timeoutMinutes  = params.timeoutMinutes  ?: 30
    def skipManifest    = params.skipManifest    ?: false
    def approverMessage = params.approverMessage ?: "Deploy ${env.IMAGE_NAME}:${releaseVersion} to PRODUCTION?"

    // Strip a leading 'v' for the image tag (v1.2.0 → 1.2.0)
    def imageTag = releaseVersion.replaceAll(/^v/, '')

    def registryUrl     = env.REGISTRY_URL     ?: 'docker.io'
    def harborProject   = env.HARBOR_PROJECT   ?: ''
    def imageName       = env.IMAGE_NAME       ?: error("productionApproval: IMAGE_NAME env var is required")
    def finalImage      = harborProject
        ? "${registryUrl}/${harborProject}/${imageName}:${imageTag}"
        : "${registryUrl}/${imageName}:${imageTag}"

    echo "=== Production Approval Gate ==="
    echo "Release version : ${releaseVersion}"
    echo "Image tag       : ${imageTag}"
    echo "Final image     : ${finalImage}"
    echo "Notify          : ${recipients ?: '(none)'}"
    echo "Timeout         : ${timeoutMinutes} minutes"

    // ── 1. SEND APPROVAL EMAIL ────────────────────────────────────────────────
    if (recipients) {
        try {
            emailext(
                subject:  "⏳ [Approval Required] ${imageName} v${imageTag} → PRODUCTION — Build #${env.BUILD_NUMBER}",
                mimeType: 'text/html',
                to:       recipients,
                body:     buildApprovalEmail(finalImage, imageTag, timeoutMinutes)
            )
            echo "✅ Approval email sent to: ${recipients}"
        } catch (Exception e) {
            echo "⚠️  Failed to send approval email (continuing): ${e.getMessage()}"
        }
    } else {
        echo "⚠️  No recipients configured — skipping approval email"
    }

    // ── 2. WAIT FOR HUMAN APPROVAL ────────────────────────────────────────────
    // Approver must type the version to confirm — prevents accidental deploys
    // and creates an explicit audit record of which version was signed off.
    def approved = false
    try {
        timeout(time: timeoutMinutes, unit: 'MINUTES') {
            def response = input(
                message:     approverMessage,
                ok:          '✅ Approve & Deploy to Production',
                parameters:  [
                    string(
                        name:         'CONFIRM_VERSION',
                        defaultValue: '',
                        description:  "Type the version to confirm deployment (expected: ${imageTag})"
                    )
                ]
            )
            def confirmed = (response instanceof Map ? response['CONFIRM_VERSION'] : response)?.trim()
            if (confirmed != imageTag) {
                env.failedStage  = "Production Approval"
                env.failedReason = "Version mismatch — entered '${confirmed}', expected '${imageTag}'"
                error "❌ Version mismatch: you entered '${confirmed}' but the release is '${imageTag}'. Deployment cancelled."
            }
        }
        approved = true
        echo "✅ Production deployment approved for version ${imageTag}"
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        // Distinguish timeout from manual abort/reject
        def cause = e.causes?.find { it instanceof org.jenkinsci.plugins.workflow.support.steps.input.Rejection }
        if (cause) {
            sendRejectionEmail(recipients, finalImage, imageTag, cause.user?.toString() ?: 'Unknown')
            env.failedStage  = "Production Approval"
            env.failedReason = "Deployment rejected by ${cause.user}"
            error "❌ Production deployment REJECTED by ${cause.user}"
        } else {
            sendTimeoutEmail(recipients, finalImage, imageTag, timeoutMinutes)
            env.failedStage  = "Production Approval"
            env.failedReason = "No approval received within ${timeoutMinutes} minutes"
            error "❌ Production deployment timed out — no approval in ${timeoutMinutes} minutes"
        }
    }

    // ── 3. UPDATE K8S MANIFEST WITH RELEASE VERSION ───────────────────────────
    if (approved && !skipManifest) {
        echo "🚀 Approval received — updating K8s manifest with release tag ${imageTag}..."
        k8sManifestScanAndUpdate(
            imageTag:      imageTag,
            commitMessage: "Release ${imageName} v${imageTag} to production [skip ci]"
        )
    }
}

// ── Email templates ───────────────────────────────────────────────────────────

private String buildApprovalEmail(String finalImage, String imageTag, int timeoutMinutes) {
    return """<!DOCTYPE html>
<html>
<head>
<style>
  body { font-family: 'Segoe UI', sans-serif; background: #f8f9fa; margin: 0; padding: 20px; }
  .container { max-width: 600px; margin: auto; background: white; border-radius: 12px;
               overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.1);
               border: 3px solid #f59e0b; }
  .header { background: linear-gradient(135deg, #d97706, #b45309); color: white;
            padding: 30px; text-align: center; }
  .content { padding: 30px; line-height: 1.6; color: #333; }
  .info { background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px; padding: 20px; margin: 20px 0; }
  .info table { border-collapse: collapse; width: 100%; }
  .info td { padding: 6px 12px 6px 0; vertical-align: top; }
  .info td:first-child { font-weight: bold; white-space: nowrap; width: 110px; }
  .approve-btn { display: inline-block; background: #16a34a; color: white;
                 padding: 12px 32px; text-decoration: none; border-radius: 8px;
                 font-weight: bold; font-size: 16px; margin-right: 12px; }
  .reject-btn  { display: inline-block; background: #dc2626; color: white;
                 padding: 12px 32px; text-decoration: none; border-radius: 8px;
                 font-weight: bold; font-size: 16px; }
  .footer { color: #9ca3af; font-size: 12px; margin-top: 24px; }
</style>
</head>
<body>
<div class="container">
  <div class="header">
    <h1 style="margin:0 0 8px 0">⏳ Production Deployment Approval</h1>
    <p style="margin:0;opacity:0.9">Human review required before release</p>
  </div>
  <div class="content">
    <p>A new image is ready to be deployed to <strong>PRODUCTION</strong>. Your approval is required.</p>

    <div class="info">
      <table>
        <tr><td>Image</td>     <td><code>${finalImage}</code></td></tr>
        <tr><td>Version</td>   <td><strong>v${imageTag}</strong></td></tr>
        <tr><td>Build</td>     <td>#${env.BUILD_NUMBER}</td></tr>
        <tr><td>Branch</td>    <td>${env.BRANCH_NAME}</td></tr>
        <tr><td>Author</td>    <td>${env.GIT_AUTHOR ?: 'unknown'}</td></tr>
        <tr><td>Commit</td>    <td><code>${(env.GIT_COMMIT_HASH ?: env.GIT_COMMIT ?: 'unknown').take(10)}</code></td></tr>
        <tr><td>Triggered</td> <td>${env.BUILD_DATE_UTC ?: new Date().toString()}</td></tr>
      </table>
    </div>

    <p style="margin-bottom:20px">
      Click the link below, <strong>type <code style="background:#fef9c3;padding:2px 6px;border-radius:4px">${imageTag}</code> in the confirmation box</strong>,
      then click Approve to deploy — or Reject to cancel.
    </p>

    <a href="${env.BUILD_URL}input" class="approve-btn">✅ Review &amp; Approve</a>
    <a href="${env.BUILD_URL}input" class="reject-btn">❌ Reject</a>

    <p class="footer">
      This request will expire in <strong>${timeoutMinutes} minutes</strong>.
      No action = automatic abort.<br>
      <a href="${env.BUILD_URL}">View build in Jenkins</a>
    </p>
  </div>
</div>
</body>
</html>"""
}

private void sendRejectionEmail(String recipients, String finalImage, String imageTag, String rejectedBy) {
    if (!recipients) return
    try {
        emailext(
            subject:  "❌ [Rejected] ${env.IMAGE_NAME} v${imageTag} production deploy cancelled",
            mimeType: 'text/html',
            to:       recipients,
            body:     """<!DOCTYPE html><html><body style="font-family:sans-serif;padding:20px">
<div style="max-width:600px;margin:auto;border:3px solid #dc2626;border-radius:12px;overflow:hidden">
  <div style="background:#dc2626;color:white;padding:20px;text-align:center">
    <h2 style="margin:0">❌ Production Deploy Rejected</h2>
  </div>
  <div style="padding:24px;line-height:1.6">
    <p>The production deployment of <code>${finalImage}</code> was <strong>rejected</strong>.</p>
    <table style="border-collapse:collapse">
      <tr><td style="padding:4px 16px 4px 0;font-weight:bold">Rejected by</td><td>${rejectedBy}</td></tr>
      <tr><td style="padding:4px 16px 4px 0;font-weight:bold">Build</td>     <td>#${env.BUILD_NUMBER}</td></tr>
      <tr><td style="padding:4px 16px 4px 0;font-weight:bold">Image</td>     <td><code>${finalImage}</code></td></tr>
    </table>
    <p style="margin-top:20px"><a href="${env.BUILD_URL}">View build in Jenkins</a></p>
  </div>
</div>
</body></html>"""
        )
    } catch (Exception e) {
        echo "⚠️  Could not send rejection email: ${e.getMessage()}"
    }
}

private void sendTimeoutEmail(String recipients, String finalImage, String imageTag, int timeoutMinutes) {
    if (!recipients) return
    try {
        emailext(
            subject:  "⏰ [Timed Out] ${env.IMAGE_NAME} v${imageTag} production deploy aborted",
            mimeType: 'text/html',
            to:       recipients,
            body:     """<!DOCTYPE html><html><body style="font-family:sans-serif;padding:20px">
<div style="max-width:600px;margin:auto;border:3px solid #9ca3af;border-radius:12px;overflow:hidden">
  <div style="background:#6b7280;color:white;padding:20px;text-align:center">
    <h2 style="margin:0">⏰ Production Deploy Timed Out</h2>
  </div>
  <div style="padding:24px;line-height:1.6">
    <p>No approval was received within <strong>${timeoutMinutes} minutes</strong>. The pipeline has been aborted.</p>
    <table style="border-collapse:collapse">
      <tr><td style="padding:4px 16px 4px 0;font-weight:bold">Build</td><td>#${env.BUILD_NUMBER}</td></tr>
      <tr><td style="padding:4px 16px 4px 0;font-weight:bold">Image</td><td><code>${finalImage}</code></td></tr>
    </table>
    <p>To retry, re-run build <a href="${env.BUILD_URL}">#${env.BUILD_NUMBER}</a> or push again to trigger a new one.</p>
  </div>
</div>
</body></html>"""
        )
    } catch (Exception e) {
        echo "⚠️  Could not send timeout email: ${e.getMessage()}"
    }
}
