def call(Map params = [:]) {
    try {
        def repo = params.repo ?: env.GIT_REPO_URL
        def creds = params.credentialsId ?: env.GIT_CREDENTIALS_ID
        def branch = params.branch ?: env.BRANCH_NAME ?: 'main'

        echo "Checking out repository: ${repo} @ (branch: ${branch})"
        checkout([$class: 'GitSCM',
                  branches: [[name: "*/${branch}"]],
                  userRemoteConfigs: [[url: repo, credentialsId: creds]]
        ])

        // Get Git info
        env.GIT_COMMIT = sh(script: 'git log -1 --pretty=%B | head -1', returnStdout: true)?.trim() ?: 'Unknown commit message'
        env.GIT_AUTHOR = sh(script: 'git log -1 --pretty=%an', returnStdout: true)?.trim() ?: 'Unknown author'
        env.GIT_COMMIT_HASH = sh(script: 'git rev-parse --short HEAD', returnStdout: true)?.trim() ?: 'unknown'
        env.GIT_AUTHOR_EMAIL = sh(script: 'git log -1 --pretty=%ae', returnStdout: true)?.trim() ?: 'unknown@example.com'
        env.GIT_AUTHOR_USERNAME = sh(script: 'git log -1 --pretty=%al', returnStdout: true)?.trim() ?: 'unknown'  // Falls back to name if %al not supported
        env.GIT_COMMIT_DATE = sh(script: 'git log -1 --pretty=%cd --date=iso', returnStdout: true)?.trim() ?: 'unknown'

        // Optional: Fallback if older Git (pre-2.25) doesn't support %al
        if (env.GIT_AUTHOR_USERNAME == '') {
            env.GIT_AUTHOR_USERNAME = env.GIT_AUTHOR_EMAIL.split('@')[0] ?: 'unknown'
        }
        echo "Git Info - Author: ${env.GIT_AUTHOR} (${env.GIT_AUTHOR_USERNAME} <${env.GIT_AUTHOR_EMAIL}>)"
        echo "Commit: ${env.GIT_COMMIT} (${env.GIT_COMMIT_HASH}) on ${env.GIT_COMMIT_DATE}"

        return [
                commitMessage: env.GIT_COMMIT,
                author: env.GIT_AUTHOR,
                authorEmail: env.GIT_AUTHOR_EMAIL,
                authorUsername: env.GIT_AUTHOR_USERNAME,
                hash: env.GIT_COMMIT_HASH
        ]
    }
    catch (e) {
        env.failedStage = params.stageName ?: "Checkout and Git Info"
        currentBuild.result = 'FAILURE'
        echo "Error during checkout or Git info retrieval: ${e.getMessage()}"
        throw e
    }
}