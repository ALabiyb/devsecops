def call(Map params = [:]) {
    def repoUrl = env.K8S_MANIFEST_REPO_URL ?: error("repoUrl is required")
    def credentialsId = env.K8S_MANIFEST_CREDENTIALS_ID ?: error("credentialsId is required")
    def branch = env.K8S_MANIFEST_BRANCH ?: 'main'
    def imageTag = env.BUILD_NUMBER
    def finalImage = "${env.REGISTRY_URL}/${imageName}:${imageTag}"

    def tempDir = "${env.WORKSPACE}/k8s-temp"

    try {
        echo "=== Kubernetes Manifest Security Scan & Update ==="
        echo "Repo: ${repoUrl} @ ${branch}"
        echo "Updating image to: ${finalImage}"

        sh "rm -rf ${tempDir} || true"

        withCredentials([usernamePassword(credentialsId: credentialsId,
        usernamevariable: 'GIT_USER',
        passwordVariable: 'GIT_PASSWORD')]) {
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
    } catch (e) {
        echo "Catch"
    }
}