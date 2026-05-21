// vars/sendUnstableNotification.groovy
def call(Map params = [:]) {
    try {
        def subject = params.subject ?: "⚠️ Pipeline Unstable: ${env.JOB_NAME} #${env.BUILD_NUMBER}"
        def recipients = params.recipients ?: env.NOTIFICATION_EMAIL

        def defaultData = [
                JOB_NAME            : env.JOB_NAME,
                BUILD_NUMBER        : env.BUILD_NUMBER,
                BUILD_URL           : env.BUILD_URL,
                BRANCH              : env.BRANCH_NAME,
                TRIGGERED_BY        : params.triggeredBy ?: 'Unknown',
                GIT_COMMIT_MESSAGE  : env.GIT_COMMIT_MESSAGE ?: 'N/A',
                GIT_AUTHOR          : env.GIT_AUTHOR ?: 'Unknown',
                GIT_AUTHOR_USERNAME : env.GIT_AUTHOR_USERNAME ?: 'unknown',
                GIT_AUTHOR_EMAIL    : env.GIT_AUTHOR_EMAIL ?: 'unknown@example.com',
                GIT_COMMIT_DATE     : env.GIT_COMMIT_DATE ?: 'unknown',
                UNSTABLE_STAGE      : env.unstableStage ?: env.failedStage ?: 'Unknown Stage',
                UNSTABLE_REASON     : env.unstableReason ?: env.failedReason ?: 'No reason captured'
        ]
        def data = defaultData + (params.data ?: [:])

        def body = getTemplate(data)

        if (recipients) {
            emailext(
                    subject: subject,
                    body: body,
                    to: recipients,
                    mimeType: 'text/html',
                    attachLog: true,
                    recipientProviders: [
                            [$class: 'DevelopersRecipientProvider'],
                            [$class: 'CulpritsRecipientProvider']
                    ]
            )
        }

        echo "⚠️ Unstable notification sent for stage: ${data.UNSTABLE_STAGE}"
    } catch (e) {
        echo "⚠️ sendUnstableNotification failed: ${e.message}"
    }
}

private String getTemplate(Map data) {
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <style>
            body { font-family: 'Segoe UI', sans-serif; background: #f8f9fa; margin: 0; padding: 20px; }
            .container { max-width: 600px; margin: auto; background: white; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.1); border: 3px solid #fd7e14; }
            .header { background: linear-gradient(135deg, #fd7e14, #c95e00); color: white; padding: 30px; text-align: center; }
            .content { padding: 30px; line-height: 1.6; color: #333; }
            .info { background: #fff3cd; border-radius: 8px; padding: 20px; margin: 20px 0; border-left: 5px solid #fd7e14; }
            .warning { background: #7d4700; color: white; padding: 15px; border-radius: 8px; margin: 20px 0; font-family: monospace; }
            .suggestions { background: #d1ecf1; border: 1px solid #bee5eb; border-radius: 8px; padding: 15px; margin: 20px 0; }
            .badge { display: inline-block; background: rgba(255,255,255,0.2); border-radius: 20px; padding: 4px 14px; font-size: 0.85em; margin-top: 8px; }
            .button { display: inline-block; background: #fd7e14; color: white; padding: 12px 28px; text-decoration: none; border-radius: 30px; font-weight: bold; }
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <h1>⚠️ Pipeline Unstable</h1>
                <p>Build #${data.BUILD_NUMBER} completed with warnings in: <strong>${data.UNSTABLE_STAGE}</strong></p>
                <span class="badge">Needs Attention — Not a Hard Failure</span>
            </div>
            <div class="content">
                <p>Hello <strong>Team</strong>,</p>
                <p>The pipeline completed but was marked <strong>UNSTABLE</strong>. This typically means a security scan, quality gate, or test check raised a warning that requires your attention. The artifact may have been built and pushed, but please review before promoting to production.</p>

                <div class="info">
                    <p><strong>Unstable Stage:</strong> ${data.UNSTABLE_STAGE}</p>
                    <p><strong>Commit Message:</strong></p>
                    <blockquote style="margin: 10px 0; padding: 10px 20px; background: #ffeeba; border-left: 4px solid #fd7e14; font-style: italic;">
                    ${data.GIT_COMMIT_MESSAGE}
                    </blockquote>
                    <p><strong>Author:</strong> ${data.GIT_AUTHOR} (${data.GIT_AUTHOR_USERNAME})</p>
                    <p><strong>Email:</strong> <a href="mailto:${data.GIT_AUTHOR_EMAIL}">${data.GIT_AUTHOR_EMAIL}</a></p>
                    <p><strong>Branch:</strong> ${data.BRANCH}</p>
                    <p><strong>Triggered By:</strong> ${data.TRIGGERED_BY}</p>
                    <p><strong>Commit Date:</strong> ${data.GIT_COMMIT_DATE}</p>
                </div>

                <div class="warning">
                    <strong>Warning Details:</strong><br>
                    ${data.UNSTABLE_REASON}
                </div>

                <div class="suggestions">
                    <p><strong>Common causes of UNSTABLE builds:</strong></p>
                    <ul>
                        <li>🔑 Gitleaks detected a hardcoded secret or API key — rotate it immediately</li>
                        <li>🛡️ OSV/OWASP found dependency vulnerabilities above threshold</li>
                        <li>📊 SonarQube quality gate did not pass</li>
                        <li>🧪 Test failures or flaky tests</li>
                    </ul>
                    <p><strong>Next Steps:</strong></p>
                    <ul>
                        <li>Review the console log for the specific warning</li>
                        <li>Fix the underlying issue and push a new commit</li>
                        <li>Do not promote this build to production until resolved</li>
                    </ul>
                </div>

                <p><a href="${data.BUILD_URL}console" class="button">View Console Log</a></p>
            </div>
        </div>
    </body>
    </html>
    """
}