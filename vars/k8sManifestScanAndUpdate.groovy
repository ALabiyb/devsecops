/**
 * k8sManifestScanAndUpdate.groovy
 * A Jenkins shared library function to update Kubernetes manifest files with OPA security scanning
 * Enhanced to support both GitLab (HTTPS) and Gitea (HTTP) repositories
 */
def call(Map params = [:]) {
    // Extract parameters with default values (falling back to environment variables)
    def repoUrl = params.repoUrl ?: env.K8S_MANIFEST_REPO_URL ?: error("repoUrl is required")

    // Support single path (manifestPath) or comma-separated list (K8S_MANIFEST_PATHS)
    def manifestPath = params.manifestPath ?: env.K8S_MANIFEST_PATHS ?: 'deployment.yaml'
    if (manifestPath instanceof String && manifestPath.contains(',')) {
        manifestPath = manifestPath.split(',').collect { it.trim() }
    }

    def branch = params.branch ?: env.K8S_MANIFEST_BRANCH ?: 'main'
    def credentialsId = params.credentialsId ?: env.K8S_MANIFEST_CREDENTIALS_ID ?: error("credentialsId is required (pass parameter or set env.K8S_MANIFEST_CREDENTIALS_ID)")
    def imageName = params.imageName ?: env.IMAGE_NAME ?: error("imageName is required (pass parameter or set env.IMAGE_NAME)")
    def imageTag = params.imageTag ?: env.BUILD_NUMBER ?: 'latest'
    def registryUrl = params.registryUrl ?: env.REGISTRY_URL ?: 'docker.io'
    def registryType = params.registryType ?: (env.REGISTRY_URL?.contains('harbor') ? 'harbor' : 'dockerhub')
    def privateRegistryUrl = params.privateRegistryUrl ?: (env.HARBOR_PROJECT ? "${registryUrl}/${env.HARBOR_PROJECT}" : '')
    def gitEmail = params.gitEmail ?: 'jenkins@ci.com'
    def gitName = params.gitName ?: 'Jenkins CI'
    def commitMessage = params.commitMessage ?: "Update ${imageName} image to tag ${imageTag} [skip ci]"

    echo "=== Updating Kubernetes Manifests with Security Scan ==="
    echo "Repository: ${repoUrl}"
    echo "Manifest Paths: ${manifestPath instanceof List ? manifestPath.join(', ') : manifestPath}"
    echo "Branch: ${branch}"
    echo "Image: ${imageName}:${imageTag}"
    
    // Ensure manifestPath is a list
    if (!(manifestPath instanceof List)) {
        manifestPath = [manifestPath]
    }

    // Initialize variables
    def tempDir = "${env.WORKSPACE}/manifest-repo-secure"
    
    try {
        def finalImageName = getFinalImageName(registryType, imageName, imageTag, registryUrl, privateRegistryUrl)
        echo "Final Image Name: ${finalImageName}"

        // Clone the manifest repository
        sh "rm -rf ${tempDir} || true"

        withCredentials([usernamePassword(
            credentialsId: credentialsId,
            usernameVariable: 'GIT_USERNAME',
            passwordVariable: 'GIT_PASSWORD'
        )]) {
            def repoInfo = getRepoInfo(repoUrl)
            if (repoInfo.protocol == 'https') {
                sh "git clone --depth 1 --branch ${branch} https://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir}"
            } else {
                sh "git clone --depth 1 --branch ${branch} http://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir}"
            }
        }

        // 1. Update Manifests
        manifestPath.each { singlePath ->
            def manifestFile = "${tempDir}/${singlePath}"
            if (!fileExists(manifestFile)) return

            sh """#!/bin/bash
                set -e
                IMAGE_BASE_NAME=\$(echo "${imageName}" | awk -F/ '{print \$NF}' | cut -d: -f1)
                IMAGE_COUNT=\$(grep -E '^[[:space:]]+image:' "${manifestFile}" | wc -l)
                
                if [ "\$IMAGE_COUNT" -eq 1 ]; then
                    sed -i "/^[[:space:]]*image:/{s|image:.*|image: ${finalImageName}|g}" "${manifestFile}"
                else
                    sed -i "/^[[:space:]]*image:.*\$IMAGE_BASE_NAME/{s|image:.*|image: ${finalImageName}|g}" "${manifestFile}"
                fi
            """
        }

        // 2. OPA Security Scan
        echo "=== Running OPA Security Scan (Conftest) ==="
        // We assume opa-k8s-security.rego exists in the root of the app workspace
        def opaPolicyFile = "${env.WORKSPACE}/opa-k8s-security.rego"
        
        if (fileExists(opaPolicyFile)) {
            sh """
                docker run --rm \
                    -v ${tempDir}:/project \
                    -v ${opaPolicyFile}:/opa-k8s-security.rego \
                    openpolicyagent/conftest test \
                    --policy /opa-k8s-security.rego \
                    /project
            """
            echo "✅ OPA Security Scan Passed"
        } else {
            echo "⚠️ Warning: OPA policy file not found at ${opaPolicyFile}. Skipping scan."
        }

        // 3. Commit and Push
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
                    
                    ${manifestPath.collect { "git add \"${it}\"" }.join('\n                    ')}
                    
                    if ! git diff --cached --quiet; then
                        git commit -m "${commitMessage}"
                        git push origin HEAD:${branch}
                        echo "✅ Changes pushed successfully"
                    else
                        echo "No changes to commit"
                    fi
                """
            }
        }

        return [success: true, finalImageName: finalImageName]

    } catch (Exception e) {
        echo "❌ Manifest Security Scan or Update Failed: ${e.getMessage()}"
        error "Manifest update aborted due to security or processing error: ${e.getMessage()}"
    } finally {
        sh "rm -rf ${tempDir} || true"
    }
}

def getFinalImageName(registryType, imageName, imageTag, registryUrl = 'docker.io', privateRegistryUrl = '') {
    switch(registryType.toLowerCase()) {
        case 'dockerhub': return "docker.io/${imageName}:${imageTag}"
        case 'harbor': case 'private':
            if (!privateRegistryUrl) error "privateRegistryUrl is required"
            return "${privateRegistryUrl}/${imageName}:${imageTag}"
        default: return "${imageName}:${imageTag}"
    }
}

def getRepoInfo(repoUrl) {
    def protocol = repoUrl.startsWith('http://') ? 'http' : 'https'
    def domain = repoUrl.replaceFirst(/https?:\/\//, '')
    return [protocol: protocol, domain: domain]
}
