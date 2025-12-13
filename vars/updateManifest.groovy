/**
 * updateK8sManifest.groovy
 * A Jenkins shared library function to update Kubernetes manifest files with new image tags
 * Enhanced to support both GitLab (HTTPS) and Gitea (HTTP) repositories
 */
def call(Map params = [:]) {
    // Extract parameters with default values
    def repoUrl = params.repoUrl ?: error("repoUrl parameter is required")
    def manifestPath = params.manifestPath ?: 'deployment.yaml'
    def branch = params.branch ?: 'main'
    def credentialsId = params.credentialsId ?: error("credentialsId parameter is required")
    def imageName = params.imageName ?: error("imageName parameter is required")
    def imageTag = params.imageTag ?: env.BUILD_NUMBER ?: 'latest'
    def registryType = params.registryType ?: 'dockerhub'
    def registryUrl = params.registryUrl ?: 'docker.io'
    def privateRegistryUrl = params.privateRegistryUrl ?: ''
    def gitEmail = params.gitEmail ?: 'jenkins@ci.com'
    def gitName = params.gitName ?: 'Jenkins CI'
    def commitMessage = params.commitMessage ?: "Update ${imageName} image to tag ${imageTag} [skip ci]"

    echo "=== Updating Kubernetes Manifest ==="
    echo "Repository: ${repoUrl}"
    echo "Manifest Path: ${manifestPath}"
    echo "Branch: ${branch}"
    echo "Image: ${imageName}:${imageTag}"
    echo "Registry Type: ${registryType}"

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

        // Define the full path to the manifest file
        def manifestFile = "${tempDir}/${manifestPath}"
        
        // Verify the manifest file exists
        if (!fileExists(manifestFile)) {
            error "Manifest file not found at path: ${manifestFile}"
        }

        echo "Updating image in manifest file: ${manifestFile}"

        // Smart image update logic
        sh """#!/bin/bash
            set -e
            
            MANIFEST_FILE="${manifestFile}"
            FINAL_IMAGE="${finalImageName}"
            IMAGE_NAME="${imageName}"
            
            echo "Processing manifest file: \$MANIFEST_FILE"
            echo "Target image: \$FINAL_IMAGE"
            
            # Count how many image lines exist in the manifest
            IMAGE_COUNT=\$(grep -c "image:" "\$MANIFEST_FILE" || true)
            echo "Found \$IMAGE_COUNT image lines in the manifest"
            
            # Backup the original file
            cp "\$MANIFEST_FILE" "\$MANIFEST_FILE.backup"
            
            if [ "\$IMAGE_COUNT" -eq 0 ]; then
                echo "❌ No image lines found in the manifest!"
                exit 1
            elif [ "\$IMAGE_COUNT" -eq 1 ]; then
                echo "Only one image found - updating any image line"
                # Update ANY image line
                sed -i "s|image:.*|image: \$FINAL_IMAGE|g" "\$MANIFEST_FILE"
            else
                echo "Multiple images found - updating only images matching our image name"
                # Extract image name without tag and registry
                IMAGE_BASE_NAME=\$(echo "\$IMAGE_NAME" | awk -F/ '{print \$NF}' | cut -d: -f1)
                echo "Looking for images containing: \$IMAGE_BASE_NAME"
                
                # Update only lines containing our image name (more flexible matching)
                if grep -q "image:.*\$IMAGE_BASE_NAME" "\$MANIFEST_FILE"; then
                    sed -i "s|image:.*\$IMAGE_BASE_NAME[^[:space:]]*|image: \$FINAL_IMAGE|g" "\$MANIFEST_FILE"
                else
                    echo "⚠️ No matching image found for \$IMAGE_BASE_NAME, updating first image line"
                    sed -i "0,/image:/s|image:.*|image: \$FINAL_IMAGE|" "\$MANIFEST_FILE"
                fi
            fi
            
            echo "Update completed. Current image lines:"
            grep "image:" "\$MANIFEST_FILE"
            
            # Verify the update worked
            if ! grep -q "image: \$FINAL_IMAGE" "\$MANIFEST_FILE"; then
                echo "❌ Failed to update image in manifest!"
                echo "Expected: image: \$FINAL_IMAGE"
                echo "Found:"
                grep "image:" "\$MANIFEST_FILE"
                exit 1
            fi
        """

        // Verify the change was made
        def updatedContent = sh(script: "grep 'image:' '${manifestFile}'", returnStdout: true).trim()
        echo "Updated image lines in manifest: ${updatedContent}"

        if (!updatedContent.contains(finalImageName)) {
            error "Failed to update image in manifest file. Expected to find: ${finalImageName}"
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
                    
                    # Add, commit and push changes
                    git add "${manifestPath}"
                    
                    # Check if there are changes to commit
                    if git diff --cached --quiet; then
                        echo "No changes to commit - image might already be up to date"
                    else
                        git commit -m "${commitMessage}"
                        echo "Pushing changes to repository..."
                        git push origin HEAD:${branch}
                        echo "✅ Changes pushed successfully"
                    fi
                """
            }
        }

        echo "✅ Successfully updated manifest file with image: ${finalImageName}"
        
        return [
            success: true,
            finalImageName: finalImageName,
            manifestFile: manifestFile,
            updatedContent: updatedContent
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
    domain = domain.replaceFirst(/\.git$/, '')
    
    echo "Repository analysis:"
    echo "  Original URL: ${repoUrl}"
    echo "  Protocol: ${protocol}"
    echo "  Domain: ${domain}"
    
    return [
        protocol: protocol,
        domain: domain
    ]
}