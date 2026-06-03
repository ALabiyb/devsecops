/**
 * updateK8sManifest.groovy
 *
 * Updates Kubernetes manifest files with new Docker image tags, then
 * commits and pushes back to the k8s manifest repository.
 *
 * KEY BEHAVIORS (prevents silent success):
 *   - Pre-flight: verifies ALL manifest files exist BEFORE making any changes
 *   - Missing file → sends warning email + PAUSES pipeline for up to 30 minutes
 *     → human clicks Proceed (after fixing) or Abort in Jenkins UI
 *     → auto-fails if no action within 30 minutes
 *   - Image update verification fails → IMMEDIATE pipeline FAILURE
 *   - No files updated → IMMEDIATE pipeline FAILURE
 *   - NEVER returns success unless all files were actually updated and verified
 *
 * Pre-flight means: only the files listed in K8S_MANIFEST_PATHS are checked,
 * not all files in the repo.
 *
 * Usage:
 *   updateK8sManifest()   // uses all env vars from Jenkinsfile
 *
 *   updateK8sManifest(
 *       waitMinutes: 15,  // override default 30-minute pause
 *       k8sDir: 'deploy/' // optional: dir to scan if using k8sManifestScanAndUpdate
 *   )
 */
def call(Map params = [:]) {

    def repoUrl           = params.repoUrl         ?: env.K8S_MANIFEST_REPO_URL         ?: error("K8S_MANIFEST_REPO_URL is required")
    def manifestPath      = params.manifestPath    ?: env.K8S_MANIFEST_PATHS             ?: 'deployment.yaml'
    def credentialsId     = params.credentialsId   ?: env.K8S_MANIFEST_CREDENTIALS_ID   ?: error("K8S_MANIFEST_CREDENTIALS_ID is required")

    // Map app branch → manifest repo branch.
    // dev/main → K8S_MANIFEST_BRANCH (default: dev)
    // uat      → K8S_MANIFEST_UAT_BRANCH  (default: uat)
    // prod     → K8S_MANIFEST_PROD_BRANCH (default: prod)
    def branch = params.branch ?: resolveManifestBranch(env.BRANCH_NAME)
    def imageName         = params.imageName       ?: env.IMAGE_NAME                     ?: error("IMAGE_NAME is required")
    def imageTag          = params.imageTag        ?: env.BUILD_NUMBER                   ?: 'latest'
    def registryUrl       = params.registryUrl     ?: env.REGISTRY_URL                  ?: 'docker.io'
    def registryType      = params.registryType    ?: (env.REGISTRY_URL?.contains('harbor') ? 'harbor' : 'dockerhub')
    def privateRegistryUrl = params.privateRegistryUrl ?: (env.HARBOR_PROJECT ? "${registryUrl}/${env.HARBOR_PROJECT}" : '')
    def gitEmail          = params.gitEmail        ?: 'donotreply@softnet.co.tz'
    def gitName           = params.gitName         ?: 'Jenkins CI'
    def commitMessage     = params.commitMessage   ?: "Update ${imageName} image to tag ${imageTag}"
    def waitMinutes       = params.waitMinutes     ?: 30   // pause duration when file not found
    def notificationEmail = params.notificationEmail ?: env.NOTIFICATION_EMAIL ?: ''

    // Normalize manifestPath to List (supports single string or comma-separated)
    if (manifestPath instanceof String && manifestPath.contains(',')) {
        manifestPath = manifestPath.split(',').collect { it.trim() }
    }
    if (!(manifestPath instanceof List)) {
        manifestPath = [manifestPath]
    }

    echo "=== Updating Kubernetes Manifests ==="
    echo "Repository:     ${repoUrl}"
    echo "Branch:         ${branch}"
    echo "Manifest paths: ${manifestPath.join(', ')}"
    echo "Image:          ${imageName}:${imageTag}"

    def tempDir = "${env.WORKSPACE}/manifest-repo"

    try {
        def finalImageName = getFinalImageName(registryType, imageName, imageTag, registryUrl, privateRegistryUrl)
        echo "Final image: ${finalImageName}"

        // Clone the k8s manifest repo
        sh "rm -rf ${tempDir} || true"
        withCredentials([usernamePassword(
            credentialsId: credentialsId,
            usernameVariable: 'GIT_USERNAME',
            passwordVariable: 'GIT_PASSWORD'
        )]) {
            def repoInfo = getRepoInfo(repoUrl)
            def baseUrl  = "${repoInfo.protocol}://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain}"
            // Try target branch first; fall back to main so the first prod push
            // creates the prod branch rather than failing.
            sh """
                git clone --depth 1 --branch ${branch} ${baseUrl} ${tempDir} 2>/dev/null || \
                git clone --depth 1 --branch main      ${baseUrl} ${tempDir}
            """
        }

        // -------------------------------------------------------------------------
        // PRE-FLIGHT CHECK — verify ALL files exist BEFORE making any changes
        // Only checks files listed in K8S_MANIFEST_PATHS (not all repo files)
        // Prevents partial updates where some files are updated and others aren't
        // -------------------------------------------------------------------------
        def missingFiles = []
        manifestPath.each { singlePath ->
            def manifestFile = "${tempDir}/${singlePath}"
            if (!fileExists(manifestFile)) {
                missingFiles.add(singlePath)
                echo "⚠️  NOT FOUND: ${manifestFile}"
            } else {
                echo "✅ Found: ${manifestFile}"
            }
        }

        if (missingFiles) {
            // Notify team immediately via email
            sendManifestWarningNotification(
                missingFiles: missingFiles,
                repoUrl: repoUrl,
                branch: branch,
                imageName: imageName,
                imageTag: imageTag,
                notificationEmail: notificationEmail
            )

            // Pause pipeline — human must fix the issue and click Proceed
            echo "⏸️  Pipeline PAUSED — waiting up to ${waitMinutes} minutes for action"
            echo "ℹ️  Go to Jenkins UI → this build → Input Required → Proceed or Abort"

            try {
                timeout(time: waitMinutes, unit: 'MINUTES') {
                    input(
                        message: """
⚠️  MANIFEST FILES NOT FOUND — ACTION REQUIRED

Missing file(s):
${missingFiles.collect { "  • ${it}" }.join('\n')}

Repository: ${repoUrl}
Branch:     ${branch}

Steps to fix:
  1. Check K8S_MANIFEST_PATHS value is correct
  2. Verify the file exists in the repo at the correct path
  3. Push the fix to branch: ${branch}

Then click PROCEED, or click ABORT to fail now.
                        """,
                        ok: 'Proceed — files have been fixed'
                    )
                }

                // Human clicked Proceed — re-validate
                echo "✅ Approved — re-validating files..."
                missingFiles.each { singlePath ->
                    // Re-pull the repo to pick up any changes made during the pause
                    sh "cd ${tempDir} && git pull origin ${branch} || true"
                    def manifestFile = "${tempDir}/${singlePath}"
                    if (!fileExists(manifestFile)) {
                        env.failedStage  = "k8s Manifest Update"
                        env.failedReason = "File still not found after approval: ${singlePath}"
                        error "❌ Still not found: ${singlePath}\nPush the file to ${repoUrl} branch ${branch} then retry"
                    }
                }
                echo "✅ All files verified — continuing"

            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                env.failedStage  = "k8s Manifest Update"
                env.failedReason = "Manifest file(s) not found, no action in ${waitMinutes} minutes: ${missingFiles.join(', ')}"
                error "❌ Pipeline failed: manifest file(s) not found — ${missingFiles.join(', ')}"
            }
        }

        // -------------------------------------------------------------------------
        // UPDATE — update image tag in each manifest file
        // -------------------------------------------------------------------------
        def updatedFiles = []

        manifestPath.each { singlePath ->
            def manifestFile = "${tempDir}/${singlePath}"

            sh """#!/bin/bash
                set -e
                MANIFEST_FILE="${manifestFile}"
                FINAL_IMAGE="${finalImageName}"
                IMAGE_BASE_NAME=\$(echo "${imageName}" | awk -F/ '{print \$NF}' | cut -d: -f1)

                echo "Updating \$MANIFEST_FILE with \$FINAL_IMAGE"

                IMAGE_COUNT=\$(grep -E '^[[:space:]]+image:' "\$MANIFEST_FILE" | wc -l)

                if [ "\$IMAGE_COUNT" -eq 0 ]; then
                    echo "❌ No image: lines found in \$MANIFEST_FILE"
                    exit 1
                fi

                cp "\$MANIFEST_FILE" "\$MANIFEST_FILE.backup"

                if grep -E "^[[:space:]]+image:.*\$IMAGE_BASE_NAME" "\$MANIFEST_FILE" > /dev/null; then
                    sed -i "/^[[:space:]]*image:.*\$IMAGE_BASE_NAME/{s|image:.*|image: \$FINAL_IMAGE|g}" "\$MANIFEST_FILE"
                elif [ "\$IMAGE_COUNT" -eq 1 ]; then
                    sed -i "/^[[:space:]]*image:/{s|image:.*|image: \$FINAL_IMAGE|g}" "\$MANIFEST_FILE"
                else
                    echo "❌ Multiple image lines found but none match '\$IMAGE_BASE_NAME':"
                    grep "^[[:space:]]*image:" "\$MANIFEST_FILE"
                    exit 1
                fi

                if grep "image: \$FINAL_IMAGE" "\$MANIFEST_FILE" > /dev/null; then
                    echo "✅ Verified: image updated to \$FINAL_IMAGE"
                else
                    echo "❌ Verification FAILED — restoring backup"
                    cp "\$MANIFEST_FILE.backup" "\$MANIFEST_FILE"
                    exit 1
                fi
            """

            // Double-check from Groovy side
            def updatedContent = sh(script: "grep 'image:' '${manifestFile}'", returnStdout: true).trim()
            if (!updatedContent.contains(finalImageName)) {
                env.failedStage  = "k8s Manifest Update"
                env.failedReason = "Image verification failed in ${singlePath}"
                error "❌ Update verification failed in ${singlePath}\nExpected: ${finalImageName}\nFound: ${updatedContent}"
            }

            updatedFiles.add([path: singlePath, file: manifestFile])
            echo "✅ Updated: ${singlePath}"
        }

        if (updatedFiles.isEmpty()) {
            env.failedStage  = "k8s Manifest Update"
            env.failedReason = "No manifest files were updated"
            error "❌ No manifest files were updated"
        }

        // -------------------------------------------------------------------------
        // COMMIT AND PUSH
        // -------------------------------------------------------------------------
        dir(tempDir) {
            withCredentials([usernamePassword(
                credentialsId: credentialsId,
                usernameVariable: 'GIT_USERNAME',
                passwordVariable: 'GIT_PASSWORD'
            )]) {
                def repoInfo = getRepoInfo(repoUrl)
                sh """
                    git config user.email "${gitEmail}"
                    git config user.name "${gitName}"
                    git remote set-url origin ${repoInfo.protocol}://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain}

                    ${updatedFiles.collect { "git add \"${it.path}\"" }.join('\n                    ')}

                    if git diff --cached --quiet; then
                        echo "ℹ️  No changes — image tag was already up to date"
                    else
                        git commit -m "${commitMessage}"
                        git push origin HEAD:${branch}
                        echo "✅ Pushed manifest update to ${branch}"
                    fi
                """
            }
        }

        echo "✅ K8s manifest update complete — image: ${finalImageName}"
        return [success: true, finalImageName: finalImageName, updatedFiles: updatedFiles]

    } catch (Exception e) {
        if (!env.failedStage) {
            env.failedStage  = "k8s Manifest Update"
            env.failedReason = e.getMessage()
        }
        echo "❌ K8s manifest update failed: ${e.getMessage()}"
        currentBuild.result = 'FAILURE'
        throw e   // always re-throw — NEVER silently succeed

    } finally {
        sh "rm -rf ${tempDir} || true"
    }
}

// -----------------------------------------------------------------------------
// Sends a warning email when manifest files are not found
// Called before the input() pause so team is notified immediately
// -----------------------------------------------------------------------------
def sendManifestWarningNotification(Map params) {
    def missingFiles      = params.missingFiles      ?: []
    def repoUrl           = params.repoUrl           ?: 'unknown'
    def branch            = params.branch            ?: 'main'
    def imageName         = params.imageName         ?: 'unknown'
    def imageTag          = params.imageTag          ?: 'unknown'
    def notificationEmail = params.notificationEmail ?: ''

    if (!notificationEmail) {
        echo "⚠️  No NOTIFICATION_EMAIL set — skipping warning email"
        return
    }

    try {
        emailext(
            subject: "⚠️  ACTION REQUIRED: K8s Manifest File Not Found — ${env.JOB_NAME} #${env.BUILD_NUMBER}",
            body: """
            <!DOCTYPE html><html><head>
            <style>
                body{font-family:'Segoe UI',sans-serif;background:#f8f9fa;margin:0;padding:20px}
                .container{max-width:600px;margin:auto;background:white;border-radius:12px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.1);border:3px solid #ffc107}
                .header{background:linear-gradient(135deg,#ffc107,#e0a800);color:#212529;padding:30px;text-align:center}
                .content{padding:30px;line-height:1.6;color:#333}
                .warning-box{background:#fff3cd;border:1px solid #ffc107;border-radius:8px;padding:20px;margin:20px 0;border-left:5px solid #ffc107}
                .steps{background:#e8f5e9;border-radius:8px;padding:15px;margin:20px 0}
                .btn{display:inline-block;background:#ffc107;color:#212529;padding:12px 28px;text-decoration:none;border-radius:30px;font-weight:bold}
                code{background:#f1f1f1;padding:2px 6px;border-radius:4px}
            </style></head><body>
            <div class="container">
                <div class="header"><h1>⚠️ Action Required</h1><p>K8s Manifest File Not Found — Pipeline Paused</p></div>
                <div class="content">
                    <p>Hello <strong>Team</strong>,</p>
                    <p>The pipeline is <strong>paused</strong> because these manifest file(s) were not found:</p>
                    <div class="warning-box">
                        <strong>Missing File(s):</strong>
                        <ul>${missingFiles.collect { "<li><code>${it}</code></li>" }.join('')}</ul>
                        <p><strong>Repository:</strong> ${repoUrl}</p>
                        <p><strong>Branch:</strong> ${branch}</p>
                        <p><strong>Deploying image:</strong> ${imageName}:${imageTag}</p>
                        <p><strong>Job:</strong> ${env.JOB_NAME} #${env.BUILD_NUMBER}</p>
                    </div>
                    <div class="steps">
                        <strong>How to fix:</strong>
                        <ol>
                            <li>Verify <code>K8S_MANIFEST_PATHS</code> path is correct in Jenkinsfile</li>
                            <li>Confirm file exists in repo at the correct path</li>
                            <li>Confirm branch is <code>${branch}</code></li>
                            <li>Push the fix, then click <strong>Proceed</strong> in Jenkins</li>
                        </ol>
                        <p>⏱️ Pipeline auto-fails if no action is taken.</p>
                    </div>
                    <p><a href="${env.BUILD_URL}input" class="btn">Go to Jenkins → Take Action</a></p>
                </div>
            </div></body></html>
            """,
            to: notificationEmail,
            mimeType: 'text/html'
        )
        echo "⚠️  Warning email sent to: ${notificationEmail}"
    } catch (Exception e) {
        echo "⚠️  Failed to send warning email (continuing): ${e.getMessage()}"
    }
}

// Returns the manifest repo branch that corresponds to the app branch.
//   dev / main / anything else → K8S_MANIFEST_BRANCH       (default: dev)
//   uat                        → K8S_MANIFEST_UAT_BRANCH   (default: uat)
//   prod                       → K8S_MANIFEST_PROD_BRANCH  (default: prod)
def resolveManifestBranch(String appBranch) {
    switch (appBranch) {
        case 'prod': return env.K8S_MANIFEST_PROD_BRANCH ?: 'prod'
        case 'uat':  return env.K8S_MANIFEST_UAT_BRANCH  ?: 'uat'
        default:     return env.K8S_MANIFEST_BRANCH       ?: 'dev'
    }
}

def getFinalImageName(registryType, imageName, imageTag, registryUrl = 'docker.io', privateRegistryUrl = '') {
    switch (registryType.toLowerCase()) {
        case 'dockerhub': return "docker.io/${imageName}:${imageTag}"
        case 'harbor':
        case 'private':
            if (!privateRegistryUrl) error "privateRegistryUrl is required for harbor registry"
            return "${privateRegistryUrl}/${imageName}:${imageTag}"
        default: return "${imageName}:${imageTag}"
    }
}

def getRepoInfo(repoUrl) {
    def protocol = repoUrl.startsWith('http://') ? 'http' : 'https'
    def domain   = repoUrl.replaceFirst(/https?:\/\//, '')
    return [protocol: protocol, domain: domain]
}