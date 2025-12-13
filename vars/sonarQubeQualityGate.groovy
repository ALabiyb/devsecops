/**
 * Quality Gate Check Function
 * File: vars/sonarQubeQualityGate.groovy
 */
def qualityGate(Map params = [:]) {
    // Default parameters
    def defaultParams = [
        timeout: 1,
        timeoutUnit: 'HOURS',
        abortPipeline: false,
        sendNotification: false,
        notificationRecipients: ''
    ]
    
    def config = defaultParams + params
    
    try {
        echo "==== Checking SonarQube Quality Gate ===="
        
        timeout(time: config.timeout, unit: config.timeoutUnit) {
            def qualityGateResult = waitForQualityGate()
            
            if (qualityGateResult.status != 'OK') {
                echo "❌ Quality Gate failed: ${qualityGateResult.status}"
                
                if (config.sendNotification && config.notificationRecipients) {
                    // Send notification about quality gate failure
                    notify([
                        subject: "❌ Quality Gate Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        recipients: config.notificationRecipients,
                        templateName: 'quality-gate-failure.html',
                        data: [
                            JOB_NAME: env.JOB_NAME,
                            BUILD_NUMBER: env.BUILD_NUMBER,
                            BUILD_URL: env.BUILD_URL,
                            QUALITY_GATE_STATUS: qualityGateResult.status,
                            BUILD_STATUS: "QUALITY_GATE_FAILED"
                        ]
                    ])
                }
                
                if (config.abortPipeline) {
                    error("Quality Gate failed with status: ${qualityGateResult.status}")
                } else {
                    echo "⚠️ Quality Gate failed but pipeline will continue"
                }
                
                return [
                    success: false,
                    status: qualityGateResult.status,
                    message: "Quality Gate failed with status: ${qualityGateResult.status}"
                ]
            } else {
                echo "✅ Quality Gate passed"
                return [
                    success: true,
                    status: qualityGateResult.status,
                    message: "Quality Gate passed successfully"
                ]
            }
        }
        
    } catch (Exception e) {
        echo "❌ Quality Gate check failed: ${e.getMessage()}"
        
        if (config.abortPipeline) {
            error("Quality Gate check failed: ${e.getMessage()}")
        }
        
        return [
            success: false,
            error: e.getMessage(),
            message: "Quality Gate check failed: ${e.getMessage()}"
        ]
    }
}