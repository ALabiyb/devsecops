/**
 * updateK8sManifest.groovy
 * A Jenkins shared library function to update Kubernetes manifest files with new image tags
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
    def gitEmail = params.gitEmail ?: 'donotreply@softnet.co.tz'
    def gitName = params.gitName ?: 'Jenkins CI'
    def commitMessage = params.commitMessage ?: "Update ${imageName} image to tag ${imageTag}"

    echo "=== Updating Kubernetes Manifests ==="
    echo "Repository: ${repoUrl}"
    echo "Manifest Paths: ${manifestPath instanceof List ? manifestPath.join(', ') : manifestPath}"
    echo "Branch: ${branch}"
    echo "Image: ${imageName}:${imageTag}"
    echo "Registry Type: ${registryType}"

    // Ensure manifestPath is a list (support both single and multiple paths)
    if (!(manifestPath instanceof List)) {
        manifestPath = [manifestPath]
    }

    // Initialize variables
    def tempDir = "${env.WORKSPACE}/manifest-repo"
    
    try {
        // Calculate final image name based on registry type
        def finalImageName = getFinalImageName(registryType, imageName, imageTag, registryUrl, privateRegistryUrl)
        echo "Final Image Name: ${finalImageName}"

        // Clone the manifest repository
        sh "rm -rf ${tempDir} || true"

        echo "Cloning repository: ${repoUrl}"

        withCredentials([usernamePassword(
            credentialsId: credentialsId,
            usernameVariable: 'GIT_USERNAME',
            passwordVariable: 'GIT_PASSWORD'
        )]) {
            // Get repository information dynamically
            def repoInfo = getRepoInfo(repoUrl)
            echo "Repository protocol: ${repoInfo.protocol}"
            echo "Repository domain: ${repoInfo.domain}"
            
            // Clone using the appropriate protocol
            if (repoInfo.protocol == 'https') {
                // For HTTPS repositories (GitLab, GitHub, etc.)
                sh """
                    # Try normal clone first
                    git clone --depth 1 --branch ${branch} https://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir} || 
                    (echo "Trying with SSL verification disabled..." && 
                     git -c http.sslVerify=false clone --depth 1 --branch ${branch} https://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir}) || 
                    (echo "Clone failed - checking repository access..." && 
                     git ls-remote https://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain} && 
                     exit 1)
                """
            } else {
                // For HTTP repositories (Gitea, local repos, etc.)
                sh """
                    # Try HTTP clone
                    git clone --depth 1 --branch ${branch} http://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir} || 
                    (echo "HTTP clone failed - checking repository access..." && 
                     git ls-remote http://\${GIT_USERNAME}:\${GIT_PASSWORD}@${repoInfo.domain} && 
                     exit 1)
                """
            }
        }

        // Process each manifest file
        def updatedFiles = []
        manifestPath.each { singlePath ->
            def manifestFile = "${tempDir}/${singlePath}"
            
            // Verify the manifest file exists
            if (!fileExists(manifestFile)) {
                echo "Warning: Manifest file not found at path: ${manifestFile}. Skipping."
                return  // continue to next file
            }

            echo "Updating image in manifest file: ${manifestFile}"

            // Smart image update logic with better handling for commented lines and multiple containers
            sh """#!/bin/bash
                set -e
                
                MANIFEST_FILE="${manifestFile}"
                FINAL_IMAGE="${finalImageName}"
                IMAGE_NAME="${imageName}"
                
                echo "Processing manifest file: \$MANIFEST_FILE"
                echo "Target image: \$FINAL_IMAGE"
                echo "Looking for image name: \$IMAGE_NAME"
                
                # Extract the base image name (last part after /)
                IMAGE_BASE_NAME=\$(echo "\$IMAGE_NAME" | awk -F/ '{print \$NF}' | cut -d: -f1)
                echo "Image base name to search for: \$IMAGE_BASE_NAME"
                
                # Count non-commented image lines (must have whitespace before image:)
                IMAGE_COUNT=\$(grep -E '^[[:space:]]+image:' "\$MANIFEST_FILE" | wc -l)
                echo "Found \$IMAGE_COUNT image lines (non-commented) in the manifest"
                
                # Backup the original file
                cp "\$MANIFEST_FILE" "\$MANIFEST_FILE.backup"
                
                if [ "\$IMAGE_COUNT" -eq 0 ]; then
                    echo "❌ No image lines found in the manifest!"
                    exit 1
                fi
                
                # Try to find and update only lines containing our image name
                if grep -E "^[[:space:]]+image:.*\$IMAGE_BASE_NAME" "\$MANIFEST_FILE" > /dev/null; then
                    echo "✔️  Found image containing '\$IMAGE_BASE_NAME' - updating only those lines"
                    # Update only lines that contain our image base name
                    sed -i "/^[[:space:]]*image:.*\$IMAGE_BASE_NAME/{s|image:.*|image: \$FINAL_IMAGE|g}" "\$MANIFEST_FILE"
                else
                    # If no matching image name found, but we have exactly 1 image, update it
                    if [ "\$IMAGE_COUNT" -eq 1 ]; then
                        echo "⚠️  No image with '\$IMAGE_BASE_NAME' found, but found 1 image line - updating it"
                        sed -i "/^[[:space:]]*image:/{s|image:.*|image: \$FINAL_IMAGE|g}" "\$MANIFEST_FILE"
                    else
                        echo "❌ Multiple images found but none match '\$IMAGE_BASE_NAME':"
                        grep "^[[:space:]]*image:" "\$MANIFEST_FILE"
                        exit 1
                    fi
                fi
                
                echo "Update completed. Current image lines:"
                grep "image:" "\$MANIFEST_FILE" || true
                
                # Verify the update worked (check for the final image anywhere in image lines)
                if grep "image: \$FINAL_IMAGE" "\$MANIFEST_FILE" > /dev/null; then
                    echo "✅ Image update verified in manifest"
                else
                    echo "❌ Failed to update image in manifest!"
                    echo "Expected to find: image: \$FINAL_IMAGE"
                    echo "Found:"
                    grep "image:" "\$MANIFEST_FILE" || echo "[no image lines found]"
                    exit 1
                fi
            """

            // Verify the change was made
            def updatedContent = sh(script: "grep 'image:' '${manifestFile}'", returnStdout: true).trim()
            echo "Updated image lines in manifest: ${updatedContent}"

            if (!updatedContent.contains(finalImageName)) {
                error "Failed to update image in manifest file ${manifestFile}. Expected to find: ${finalImageName}"
            }

            updatedFiles.add([
                path: singlePath,
                file: manifestFile,
                updatedContent: updatedContent
            ])
        }

        if (updatedFiles.isEmpty()) {
            error "No manifest files were successfully updated."
        }

        // Commit and push the changes
        dir(tempDir) {
            withCredentials([usernamePassword(
                credentialsId: credentialsId,
                usernameVariable: 'GIT_USERNAME', 
                passwordVariable: 'GIT_PASSWORD'
            )]) {
                def repoInfo = getRepoInfo(repoUrl)
                def protocol = repoInfo.protocol
                def domain = repoInfo.domain
                
                sh """
                    # Configure git user
                    git config user.email "${gitEmail}"
                    git config user.name "${gitName}"
                    
                    # Update remote URL with credentials for pushing using the correct protocol
                    git remote set-url origin ${protocol}://\${GIT_USERNAME}:\${GIT_PASSWORD}@${domain}
                    
                    # Add all updated manifest files
                    ${updatedFiles.collect { "git add \"${it.path}\"" }.join('\n                    ')}
                    
                    # Check if there are changes to commit
                    if git diff --cached --quiet; then
                        echo "No changes to commit - images might already be up to date"
                    else
                        git commit -m "${commitMessage}"
                        echo "Pushing changes to repository..."
                        git push origin HEAD:${branch}
                        echo "✅ Changes pushed successfully"
                    fi
                """
            }
        }

        echo "✅ Successfully updated ${updatedFiles.size()} manifest file(s) with image: ${finalImageName}"
        
        return [
            success: true,
            finalImageName: finalImageName,
            updatedFiles: updatedFiles
        ]

    } catch (Exception e) {
        echo "❌ Failed to update manifest: ${e.getMessage()}"
        return [
            success: false,
            error: e.getMessage(),
            errorType: 'MANIFEST_UPDATE_ERROR'
        ]
    } finally {
        // Clean up temporary directory
        sh "rm -rf ${tempDir} || true"
    }
}

/**
 * Helper function to get final image name based on registry type
 */
def getFinalImageName(registryType, imageName, imageTag, registryUrl = 'docker.io', privateRegistryUrl = '') {
    switch(registryType.toLowerCase()) {
        case 'dockerhub':
            return "docker.io/${imageName}:${imageTag}"
        case 'harbor':
        case 'private':
            if (!privateRegistryUrl) {
                error "privateRegistryUrl is required for harbor/private registry type"
            }
            return "${privateRegistryUrl}/${imageName}:${imageTag}"
        default:
            return "${imageName}:${imageTag}"
    }
}

/**
 * Helper function to extract repository information dynamically
 * Handles both HTTP and HTTPS repositories
 */
def getRepoInfo(repoUrl) {
    def protocol = 'https' // default to HTTPS
    def domain = ''
    
    // Extract protocol and domain
    if (repoUrl.startsWith('https://')) {
        protocol = 'https'
        domain = repoUrl.replaceFirst('https://', '')
    } else if (repoUrl.startsWith('http://')) {
        protocol = 'http'
        domain = repoUrl.replaceFirst('http://', '')
    } else {
        // Assume HTTPS if no protocol specified
        protocol = 'https'
        domain = repoUrl
    }
    
    // Remove .git suffix if present
    //domain = domain.replaceFirst(/\.git$/, '')
    
    echo "Repository analysis:"
    echo "  Original URL: ${repoUrl}"
    echo "  Protocol: ${protocol}"
    echo "  Domain: ${domain}"
    
    return [
        protocol: protocol,
        domain: domain
    ]
}
