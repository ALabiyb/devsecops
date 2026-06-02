/**
 * k8sManifestScanAndUpdate.groovy
 *
 * Combines: image tag update + OPA K8s security scan + commit & push
 * Use this instead of updateK8sManifest() when you want to enforce K8s
 * security policies BEFORE pushing the manifest update.
 *
 * Scan order:
 *   1. Clone manifest repo
 *   2. Pre-flight: verify all manifest files exist (pause if missing)
 *   3. Update image tags in manifest files
 *   4. OPA Conftest scan of updated manifests (fails if policies violated)
 *   5. Only if scan passes → commit and push
 *
 * This prevents pushing non-compliant manifests to the k8s repo.
 *
 * OPA policies checked (opa-k8s-security.rego):
 *   ✅ Namespace required on all resources
 *   ✅ No root containers
 *   ✅ runAsNonRoot: true
 *   ✅ No privileged containers
 *   ✅ No allowPrivilegeEscalation
 *   ✅ readOnlyRootFilesystem: true
 *   ✅ Resource limits defined
 *   ✅ No hostPID / hostIPC
 *   ✅ No dangerous hostPath mounts
 *   ✅ No :latest image tags
 *   ✅ Services must be NodePort
 *
 * Usage:
 *   k8sManifestScanAndUpdate()   // uses all env vars
 *   k8sManifestScanAndUpdate(waitMinutes: 15)
 */
def call(Map params = [:]) {

    def repoUrl            = params.repoUrl         ?: env.K8S_MANIFEST_REPO_URL         ?: error("K8S_MANIFEST_REPO_URL is required")
    def manifestPath       = params.manifestPath    ?: env.K8S_MANIFEST_PATHS             ?: 'deployment.yaml'
    def branch             = params.branch          ?: env.K8S_MANIFEST_BRANCH            ?: 'main'
    def credentialsId      = params.credentialsId   ?: env.K8S_MANIFEST_CREDENTIALS_ID   ?: error("K8S_MANIFEST_CREDENTIALS_ID is required")
    def imageName          = params.imageName       ?: env.IMAGE_NAME                     ?: error("IMAGE_NAME is required")
    def imageTag           = params.imageTag        ?: env.BUILD_NUMBER                   ?: 'latest'
    def registryUrl        = params.registryUrl     ?: env.REGISTRY_URL                  ?: 'docker.io'
    def registryType       = params.registryType    ?: (env.REGISTRY_URL?.contains('harbor') ? 'harbor' : 'dockerhub')
    def privateRegistryUrl = params.privateRegistryUrl ?: (env.HARBOR_PROJECT ? "${registryUrl}/${env.HARBOR_PROJECT}" : '')
    def gitEmail           = params.gitEmail        ?: 'donotreply@softnet.co.tz'
    def gitName            = params.gitName         ?: 'Jenkins CI'
    def commitMessage      = params.commitMessage   ?: "Update ${imageName} image to tag ${imageTag}"
    def waitMinutes        = params.waitMinutes     ?: 30
    def notificationEmail  = params.notificationEmail ?: env.NOTIFICATION_EMAIL ?: ''

    // Normalize manifestPath to List
    if (manifestPath instanceof String && manifestPath.contains(',')) {
        manifestPath = manifestPath.split(',').collect { it.trim() }
    }
    if (!(manifestPath instanceof List)) {
        manifestPath = [manifestPath]
    }

    echo "=== K8s Manifest Scan and Update ==="
    echo "Repository:     ${repoUrl}"
    echo "Branch:         ${branch}"
    echo "Manifest paths: ${manifestPath.join(', ')}"
    echo "Image:          ${imageName}:${imageTag}"

    def tempDir = "${env.WORKSPACE}/manifest-repo-secure"

    try {
        def finalImageName = getFinalImageName(registryType, imageName, imageTag, registryUrl, privateRegistryUrl)
        echo "Final image: ${finalImageName}"

        // Clone
        sh "rm -rf ${tempDir} || true"
        withCredentials([usernamePassword(
            credentialsId: credentialsId,
            usernameVariable: 'GIT_USERNAME',
            passwordVariable: 'GIT_PASSWORD'
        )]) {
            def repoInfo = getRepoInfo(repoUrl)
            if (repoInfo.protocol == 'https') {
                sh """
                    git clone --depth 1 --branch ${branch} https://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir} ||
                    (git -c http.sslVerify=false clone --depth 1 --branch ${branch} https://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir})
                """
            } else {
                sh "git clone --depth 1 --branch ${branch} http://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir}"
            }
        }

        // ── PRE-FLIGHT CHECK ──────────────────────────────────────────────────
        def missingFiles = []
        manifestPath.each { singlePath ->
            if (!fileExists("${tempDir}/${singlePath}")) {
                missingFiles.add(singlePath)
                echo "⚠️  NOT FOUND: ${tempDir}/${singlePath}"
            } else {
                echo "✅ Found: ${singlePath}"
            }
        }

        if (missingFiles) {
            sendManifestWarningNotification(
                missingFiles: missingFiles,
                repoUrl: repoUrl,
                branch: branch,
                imageName: imageName,
                imageTag: imageTag,
                notificationEmail: notificationEmail
            )

            echo "⏸️  Pipeline PAUSED — waiting up to ${waitMinutes} minutes for action"
            try {
                timeout(time: waitMinutes, unit: 'MINUTES') {
                    input(
                        message: "⚠️  Manifest file(s) not found:\n${missingFiles.join('\n')}\n\nFix and push to ${branch}, then click Proceed.",
                        ok: 'Proceed — files fixed'
                    )
                }
                // Re-pull after fix
                sh "cd ${tempDir} && git pull origin ${branch} || true"
                missingFiles.each { singlePath ->
                    if (!fileExists("${tempDir}/${singlePath}")) {
                        error "❌ Still not found: ${singlePath}"
                    }
                }
            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                env.failedStage  = "k8s Manifest Scan and Update"
                env.failedReason = "Manifest not found, no action in ${waitMinutes} minutes: ${missingFiles.join(', ')}"
                error "❌ No action taken — pipeline failed"
            }
        }

        // ── UPDATE IMAGE TAGS ─────────────────────────────────────────────────
        def updatedFiles = []
        manifestPath.each { singlePath ->
            def manifestFile = "${tempDir}/${singlePath}"
            sh """#!/bin/bash
                set -e
                IMAGE_BASE_NAME=\$(echo "${imageName}" | awk -F/ '{print \$NF}' | cut -d: -f1)
                IMAGE_COUNT=\$(grep -E '^[[:space:]]+image:' "${manifestFile}" | wc -l)
                cp "${manifestFile}" "${manifestFile}.backup"

                if [ "\$IMAGE_COUNT" -eq 0 ]; then
                    echo "❌ No image: lines in ${manifestFile}"; exit 1
                fi

                if grep -E "^[[:space:]]+image:.*\$IMAGE_BASE_NAME" "${manifestFile}" > /dev/null; then
                    sed -i "/^[[:space:]]*image:.*\$IMAGE_BASE_NAME/{s|image:.*|image: ${finalImageName}|g}" "${manifestFile}"
                elif [ "\$IMAGE_COUNT" -eq 1 ]; then
                    sed -i "/^[[:space:]]*image:/{s|image:.*|image: ${finalImageName}|g}" "${manifestFile}"
                else
                    echo "❌ Multiple images, none match \$IMAGE_BASE_NAME"; exit 1
                fi

                if ! grep "image: ${finalImageName}" "${manifestFile}" > /dev/null; then
                    cp "${manifestFile}.backup" "${manifestFile}"
                    echo "❌ Verification failed"; exit 1
                fi
                echo "✅ Updated: ${singlePath}"
            """

            def content = sh(script: "grep 'image:' '${manifestFile}'", returnStdout: true).trim()
            if (!content.contains(finalImageName)) {
                env.failedStage  = "k8s Manifest Scan and Update"
                env.failedReason = "Image verification failed in ${singlePath}"
                error "❌ Verification failed in ${singlePath}"
            }
            updatedFiles.add([path: singlePath, file: manifestFile])
        }

        // ── OPA SCAN — scan AFTER update, BEFORE pushing ──────────────────────
        // Policy fetched from shared library — no copy needed in project repos
        echo "=== OPA Security Scan of Updated Manifests ==="
        def opaPolicyFile = "${env.WORKSPACE}/opa-k8s-security.rego"
        writeFile file: opaPolicyFile,
                  text: libraryResource('opa/opa-k8s-security.rego')

        sh """
            docker run --rm \
                -v ${tempDir}:/project \
                -v ${opaPolicyFile}:/opa-k8s-security.rego \
                openpolicyagent/conftest:latest test \
                --policy /opa-k8s-security.rego \
                /project
        """
        echo "✅ OPA K8s security scan passed"

        // ── COMMIT AND PUSH ───────────────────────────────────────────────────
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
                        echo "ℹ️  No changes — already up to date"
                    else
                        git commit -m "${commitMessage}"
                        git push origin HEAD:${branch}
                        echo "✅ Pushed to ${branch}"
                    fi
                """
            }
        }

        echo "✅ K8s manifest scan + update complete — image: ${finalImageName}"
        return [success: true, finalImageName: finalImageName, updatedFiles: updatedFiles]

    } catch (Exception e) {
        if (!env.failedStage) {
            env.failedStage  = "k8s Manifest Scan and Update"
            env.failedReason = e.getMessage()
        }
        echo "❌ Failed: ${e.getMessage()}"
        currentBuild.result = 'FAILURE'
        throw e

    } finally {
        sh "rm -rf ${tempDir} || true"
    }
}

// Same warning email as updateK8sManifest.groovy
def sendManifestWarningNotification(Map params) {
    def missingFiles      = params.missingFiles      ?: []
    def notificationEmail = params.notificationEmail ?: ''
    if (!notificationEmail) { echo "⚠️  No email set — skipping warning"; return }
    try {
        emailext(
            subject: "⚠️  ACTION REQUIRED: K8s Manifest Not Found — ${env.JOB_NAME} #${env.BUILD_NUMBER}",
            body: """<html><body style="font-family:sans-serif">
                <div style="max-width:600px;margin:auto;border:3px solid #ffc107;border-radius:12px;overflow:hidden">
                    <div style="background:#ffc107;padding:20px;text-align:center"><h2>⚠️ Manifest File Not Found</h2></div>
                    <div style="padding:20px">
                        <p>Missing: <ul>${missingFiles.collect { "<li><code>${it}</code></li>" }.join('')}</ul></p>
                        <p>Repository: ${params.repoUrl} (branch: ${params.branch})</p>
                        <p>Image: ${params.imageName}:${params.imageTag}</p>
                        <p>Pipeline paused — <a href="${env.BUILD_URL}input">click here to take action</a></p>
                    </div>
                </div></body></html>""",
            to: notificationEmail,
            mimeType: 'text/html'
        )
    } catch (Exception e) {
        echo "⚠️  Warning email failed: ${e.getMessage()}"
    }
}

def getFinalImageName(registryType, imageName, imageTag, registryUrl = 'docker.io', privateRegistryUrl = '') {
    switch (registryType.toLowerCase()) {
        case 'dockerhub': return "docker.io/${imageName}:${imageTag}"
        case 'harbor':
        case 'private':
            if (!privateRegistryUrl) error "privateRegistryUrl required"
            return "${privateRegistryUrl}/${imageName}:${imageTag}"
        default: return "${imageName}:${imageTag}"
    }
}

def getRepoInfo(repoUrl) {
    return [
        protocol: repoUrl.startsWith('http://') ? 'http' : 'https',
        domain: repoUrl.replaceFirst(/https?:\/\//, '')
    ]
}