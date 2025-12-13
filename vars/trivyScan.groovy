def call(Map params = [:]) {
    def imageName = params.imageName
    def severity = params.severity ?: 'CRITICAL,HIGH'
    def format = params.format ?: 'table'
    def outputFile = params.outputFile ?: "trivy-report.txt"
    def failOnVuln = params.containsKey('failOnVuln') ? params.failOnVuln : true

    if (!imageName) {
        error "imageName is required for trivyScan"
    }

    if (!isTrivyInstalled()) {
        error "Trivy is not installed. Please install Trivy before running the scan."
    }

    echo "Scanning image: ${imageName} with Trivy..."

    int exitCode = sh(
        // script: "trivy image --no-progress --severity ${severity} --format ${format} ${imageName} > ${outputFile} 2>&1",
        script: "trivy image --severity ${severity} --format ${format} ${imageName} > ${outputFile} 2>&1",
        returnStatus: true
    )

    def scanResult = readFile(outputFile).trim()
    echo "Trivy scan result saved to ${outputFile}"

    boolean vulnerabilitiesFound = (scanResult =~ /(?i)\bCRITICAL\b/ || scanResult =~ /(?i)\bHIGH\b/ || scanResult =~ /(?i)\bMEDIUM\b/)

    if (failOnVuln && vulnerabilitiesFound) {
        error "Vulnerabilities found in image: ${imageName}\nSee report: ${outputFile}"
    }

    return [
        vulnerabilitiesFound: vulnerabilitiesFound,
        report: scanResult,
        exitCode: exitCode,
        reportFile: outputFile // <-- Return the file path for email attachment
    ]
}

def isTrivyInstalled() {
    return sh(script: "which trivy", returnStatus: true) == 0
}

