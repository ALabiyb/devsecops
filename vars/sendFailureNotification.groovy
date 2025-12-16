// vars/sendFailureNotification.groovy
def call(Map params = [:]) {
    try {
        def subject = params.subject ?: "❌ Pipeline Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}"
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
                FAILED_STAGE        : env.failedStage ?: 'Unknown Stage',
                FAILURE_ERROR       : env.failureError ?: 'No error message captured'
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

        echo "❌ Failure notification sent for stage: ${data.FAILED_STAGE}"
    } catch (e) {
        echo "⚠️ sendFailureNotification failed: ${e.message}"
    }
}

private String getTemplate(Map data) {
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <style>
            body { font-family: 'Segoe UI', sans-serif; background: #f8f9fa; margin: 0; padding: 20px; }
            .container { max-width: 600px; margin: auto; background: white; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.1); border: 3px solid #dc3545; }
            .header { background: linear-gradient(135deg, #dc3545, #a71d2a); color: white; padding: 30px; text-align: center; }
            .content { padding: 30px; line-height: 1.6; color: #333; }
            .info { background: #f8d7da; border-radius: 8px; padding: 20px; margin: 20px 0; border-left: 5px solid #dc3545; }
            .error { background: #721c24; color: white; padding: 15px; border-radius: 8px; margin: 20px 0; font-family: monospace; }
            .suggestions { background: #fff3cd; border: 1px solid #ffeaa7; border-radius: 8px; padding: 15px; margin: 20px 0; }
            .button { display: inline-block; background: #dc3545; color: white; padding: 12px 28px; text-decoration: none; border-radius: 30px; font-weight: bold; }
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <h1>❌ Pipeline Failed</h1>
                <p>Build #${data.BUILD_NUMBER} failed in stage: <strong>${data.FAILED_STAGE}</strong></p>
            </div>
            <div class="content">
                <p>Hello <strong>Team</strong>,</p>
                <p>The pipeline has failed. Please investigate immediately.</p>
                
                <div class="info">
                    <p><strong>Failed Stage:</strong> ${data.FAILED_STAGE}</p>
                    <p><strong>Commit Message:</strong></p>
                    <blockquote style="margin: 10px 0; padding: 10px 20px; background: #f5c6cb; border-left: 4px solid #dc3545; font-style: italic;">
                    ${data.GIT_COMMIT_MESSAGE}
                    </blockquote>
                    <p><strong>Author:</strong> ${data.GIT_AUTHOR} (${data.GIT_AUTHOR_USERNAME})</p>
                    <p><strong>Email:</strong> <a href="mailto:${data.GIT_AUTHOR_EMAIL}">${data.GIT_AUTHOR_EMAIL}</a></p>
                    <p><strong>Branch:</strong> ${data.BRANCH}</p>
                </div>

                <div class="error">
                    <strong>Error Message:</strong><br>
                    ${data.FAILURE_ERROR}
                </div>

                <div class="suggestions">
                    <p><strong>Next Steps:</strong></p>
                    <ul>
                        <li>Click below to view full logs</li>
                        <li>Reproduce locally if possible</li>
                        <li>Fix and push a new commit</li>
                    </ul>
                </div>
                
                <p><a href="${data.BUILD_URL}console" class="button">View Console Log</a></p>
            </div>
        </div>
    </body>
    </html>
    """
}