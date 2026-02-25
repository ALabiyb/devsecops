# DevSecOps Pipeline - Missing Components & K8s Scan Fix

## 🐛 Critical Bug: K8s Manifest Scan

### Current Issue
The K8s OPA security scan in `vulnScanApplicationImage.groovy` **will fail** because:

1. **Wrong Location**: Runs during "Build Docker Image" stage
2. **Wrong Directory**: Scans application repo, not K8s manifest repo
3. **Wrong Timing**: Runs before K8s manifests are cloned

### Current Code (BROKEN)
```groovy
// In vulnScanApplicationImage.groovy - Stage: Build Docker Image
"k8s security check OPA": {
    sh """
    docker run --rm -v "\$(pwd)":/project openpolicyagent/conftest test --policy opa-k8s-security.rego k8s-manifest
    """
}
```

**Problem**: `$(pwd)` points to your application repo, but K8s manifests are in a separate repo!

---

## ✅ Recommended Fix

### Option 1: Move K8s Scan to Correct Stage (Recommended)

Update `k8sManifestScanAndUpdate.groovy` to include the security scan:

```groovy
def call() {
    def repoUrl = env.K8S_MANIFEST_REPO_URL ?: error("repoUrl is required")
    def credentialsId = env.K8S_MANIFEST_CREDENTIALS_ID ?: error("credentialsId is required")
    def branch = env.K8S_MANIFEST_BRANCH ?: 'main'
    def tempDir = "${env.WORKSPACE}/k8s-temp"

    try {
        echo "=== Kubernetes Manifest Security Scan & Update ==="
        
        // Step 1: Clone K8s manifest repo
        sh "rm -rf ${tempDir} || true"
        
        withCredentials([usernamePassword(credentialsId: credentialsId,
                                         usernameVariable: 'GIT_USER',
                                         passwordVariable: 'GIT_PASSWORD')]) {
            def repoInfo = getRepoInfo(repoUrl)
            
            if (repoInfo.protocol == 'https') {
                sh """
                    git clone --depth 1 --branch ${branch} \
                    https://\${GIT_USER}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir}
                """
            } else {
                sh """
                    git clone --depth 1 --branch ${branch} \
                    http://\${GIT_USER}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir}
                """
            }
        }
        
        // Step 2: Run OPA Security Scan on K8s Manifests
        echo "🔍 Running OPA security scan on K8s manifests..."
        sh """
            docker run --rm \
                -v ${tempDir}:/project \
                -v ${env.WORKSPACE}/opa-k8s-security.rego:/opa-k8s-security.rego \
                openpolicyagent/conftest test \
                --policy /opa-k8s-security.rego \
                /project/*.yaml /project/**/*.yaml || true
        """
        
        // Step 3: Update manifests with new image tag
        echo "📝 Updating K8s manifests with new image..."
        // ... rest of your update logic
        
        echo "✅ K8s manifest scan and update completed"
        
    } catch (Exception e) {
        env.failedStage = "K8s Manifest Scan & Update"
        env.failedReason = e.getMessage()
        throw e
    }
}
```

### Option 2: Keep K8s Manifests in Same Repo

If you prefer to keep K8s manifests in the **same repository** as your application:

1. Create a `k8s-manifest/` folder in your app repo
2. The current scan will work
3. Remove the separate K8s manifest cloning stage

---

## 📋 Missing DevSecOps Components

### 1. Unit Tests (Currently Placeholder)

**Current State**:
```groovy
stage('Unit Tests - JUnit and Jacoco') {
    steps {
        script {
            echo "Running unit tests..."  // ❌ Just a placeholder
        }
    }
}
```

**Recommended Implementation**:
```groovy
stage('Unit Tests - JUnit and Jacoco') {
    steps {
        script {
            echo "=== Running Unit Tests with Coverage ==="
            sh 'mvn test jacoco:report'
            
            // Publish JUnit results
            junit '**/target/surefire-reports/*.xml'
            
            // Publish JaCoCo coverage report
            jacoco(
                execPattern: '**/target/jacoco.exec',
                classPattern: '**/target/classes',
                sourcePattern: '**/src/main/java'
            )
            
            // Optional: Enforce coverage threshold
            sh '''
                mvn jacoco:check -Djacoco.haltOnFailure=true \
                    -Djacoco.line.coverage.minimum=0.80
            '''
        }
    }
}
```

**Required**:
- JUnit plugin installed
- JaCoCo plugin installed
- JaCoCo configured in `pom.xml`

---

### 2. Mutation Tests - PIT (Currently Placeholder)

**Current State**:
```groovy
stage('Mutation Tests - PIT') {
    steps {
        script {
            echo "Running mutation tests..."  // ❌ Just a placeholder
        }
    }
}
```

**Recommended Implementation**:
```groovy
stage('Mutation Tests - PIT') {
    steps {
        script {
            echo "=== Running Mutation Tests with PIT ==="
            
            // Run PIT mutation testing
            sh 'mvn org.pitest:pitest-maven:mutationCoverage'
            
            // Publish PIT report
            publishHTML([
                allowMissing: false,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: 'target/pit-reports',
                reportFiles: 'index.html',
                reportName: 'PIT Mutation Report'
            ])
            
            // Optional: Check mutation score threshold
            sh '''
                MUTATION_SCORE=$(grep -oP 'mutationCoverage>\\K[0-9]+' target/pit-reports/mutations.xml | head -1)
                if [ "$MUTATION_SCORE" -lt 75 ]; then
                    echo "Mutation score $MUTATION_SCORE% is below threshold 75%"
                    exit 1
                fi
            '''
        }
    }
}
```

**Required**:
- HTML Publisher plugin
- PIT configured in `pom.xml`:
```xml
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <version>1.15.3</version>
    <configuration>
        <targetClasses>
            <param>com.yourpackage.*</param>
        </targetClasses>
        <targetTests>
            <param>com.yourpackage.*</param>
        </targetTests>
        <mutationThreshold>75</mutationThreshold>
    </configuration>
</plugin>
```

---

### 3. Secret Scanning (Missing)

**Add New Stage**:
```groovy
stage('Secret Scanning') {
    steps {
        script {
            echo "=== Scanning for Secrets ==="
            
            // Using Gitleaks
            sh '''
                docker run --rm \
                    -v $(pwd):/path \
                    zricethezav/gitleaks:latest \
                    detect --source /path --verbose --no-git
            '''
            
            // Or using TruffleHog
            sh '''
                docker run --rm \
                    -v $(pwd):/proj \
                    trufflesecurity/trufflehog:latest \
                    filesystem /proj --json
            '''
        }
    }
}
```

**Insert After**: "Checkout and Git Info" stage

---

### 4. License Compliance Scanning (Missing)

**Add to Vulnerability Scan Stage**:
```groovy
"License Compliance": {
    sh '''
        mvn license:add-third-party
        mvn license:download-licenses
    '''
    
    // Or use FOSSA
    sh '''
        docker run --rm \
            -v $(pwd):/app \
            -e FOSSA_API_KEY=${FOSSA_API_KEY} \
            fossas/fossa-cli \
            analyze
    '''
}
```

---

### 5. DAST - Dynamic Application Security Testing (Missing)

**Add New Stage After Deployment**:
```groovy
stage('DAST - Dynamic Security Testing') {
    steps {
        script {
            echo "=== Running DAST with OWASP ZAP ==="
            
            // Assumes app is deployed and accessible
            def targetUrl = env.APP_URL ?: 'http://localhost:8080'
            
            sh """
                docker run --rm \
                    -v \$(pwd):/zap/wrk:rw \
                    -t owasp/zap2docker-stable \
                    zap-baseline.py \
                    -t ${targetUrl} \
                    -r zap-report.html \
                    -J zap-report.json || true
            """
            
            // Publish ZAP report
            publishHTML([
                reportDir: '.',
                reportFiles: 'zap-report.html',
                reportName: 'OWASP ZAP Security Report'
            ])
        }
    }
}
```

**Note**: Requires application to be deployed and accessible

---

### 6. Container Image Signing (Missing)

**Add After Docker Push**:
```groovy
stage('Sign Container Image') {
    steps {
        script {
            echo "=== Signing Container Image with Cosign ==="
            
            def imageName = env.FINAL_IMAGE_NAME
            
            withCredentials([file(credentialsId: 'cosign-key', variable: 'COSIGN_KEY')]) {
                sh """
                    docker run --rm \
                        -v \${COSIGN_KEY}:/cosign.key \
                        gcr.io/projectsigstore/cosign:latest \
                        sign --key /cosign.key ${imageName}
                """
            }
        }
    }
}
```

---

## 🎯 Priority Recommendations

### High Priority (Fix Now)
1. ✅ **Fix K8s OPA scan** - Move to correct stage
2. ✅ **Implement Unit Tests** - Essential for quality
3. ✅ **Add Secret Scanning** - Critical security gap

### Medium Priority (Add Soon)
4. ⚠️ **Implement Mutation Tests** - Improve test quality
5. ⚠️ **Add License Compliance** - Legal/compliance requirement
6. ⚠️ **Container Image Signing** - Supply chain security

### Low Priority (Nice to Have)
7. 📋 **Add DAST** - Requires deployed environment
8. 📋 **Add SBOM Generation** - Software Bill of Materials

---

## 📊 Complete DevSecOps Pipeline Flow

```
1. Checkout Code
2. Secret Scanning ← ADD THIS
3. Build Artifact
4. Unit Tests ← IMPLEMENT THIS
5. Mutation Tests ← IMPLEMENT THIS
6. SAST (SonarQube) ✅
7. License Compliance ← ADD THIS
8. Dependency Scan ✅
9. Dockerfile Security (OPA) ✅
10. Container Scan (Trivy) ✅
11. Build & Push Image ✅
12. Sign Image ← ADD THIS
13. Clone K8s Manifests ✅
14. K8s Security Scan (OPA) ← FIX THIS
15. Update K8s Manifests ✅
16. DAST (if deployed) ← ADD THIS
17. Notifications ✅
```

---

## 🔧 Quick Fix for K8s Scan

**Immediate workaround** - Remove the broken K8s scan from `vulnScanApplicationImage.groovy`:

1. Remove lines 34-38 from `vulnScanApplicationImage.groovy`
2. Add K8s scan to `k8sManifestScanAndUpdate.groovy` after cloning
3. Ensure `opa-k8s-security.rego` is in your **application repo** (to be mounted into container)

Would you like me to create the updated files with these fixes?
