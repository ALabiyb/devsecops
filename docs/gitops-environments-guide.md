# GitOps Environments Guide
## Full Pipeline → ArgoCD → Kubernetes

**Maintained by**: DevOps Engineering — softnethq.co.tz  
**Last Updated**: 2026-06-03

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Branch Strategy](#branch-strategy)
3. [Full Pipeline Flow (Jenkins → ArgoCD)](#full-pipeline-flow)
4. [Manifest Repository Structure](#manifest-repository-structure)
5. [Jenkins Pipeline — Multi-Environment Behaviour](#jenkins-pipeline--multi-environment-behaviour)
6. [ArgoCD Application Setup](#argocd-application-setup)
7. [Dev + UAT on the Same Cluster (Namespace Isolation)](#dev--uat-on-the-same-cluster-namespace-isolation)
8. [Separate Production Cluster — How to Add](#separate-production-cluster--how-to-add)
9. [Applying ArgoCD Applications](#applying-argocd-applications)
10. [Replicating for a New Project](#replicating-for-a-new-project)

---

## Architecture Overview

```
 GitLab (192.168.15.85)
 ┌────────────────────────────┐
 │  App Repo                  │      Jenkins (192.168.200.78)
 │  branch: dev  ─────────────┼──────► Build → Scan → Push image:42
 │  branch: uat  ─────────────┼──────► Build → Scan → Push image:43
 │  branch: prod ─────────────┼──────► Build → Scan → [APPROVAL] → Push image:1.2.0
 └────────────────────────────┘            │
                                           │  updates image tag in
                                           ▼
 GitLab (192.168.15.85)            Harbor Registry
 ┌────────────────────────────┐    (harbor.devops.softnethq.co.tz)
 │  Manifest Repo             │
 │  branch: dev  ◄────────────┼── image: harbor/.../k8s-dashboard:42
 │  branch: uat  ◄────────────┼── image: harbor/.../k8s-dashboard:43
 │  branch: prod ◄────────────┼── image: harbor/.../k8s-dashboard:1.2.0
 └────────────────────────────┘
          │  ArgoCD polls every 3 min
          ▼
 Kubernetes Cluster (dev-mastr)
 ┌──────────────────────────────────────────┐
 │  namespace: k8s-dashboard-dev            │◄── ArgoCD app: k8s-dashboard-dev
 │  namespace: k8s-dashboard-uat            │◄── ArgoCD app: k8s-dashboard-uat
 │  namespace: k8s-dashboard                │◄── ArgoCD app: k8s-dashboard-prod
 └──────────────────────────────────────────┘
          │  (future)
          ▼
 Prod Cluster (separate)
 ┌──────────────────────────────────────────┐
 │  namespace: k8s-dashboard                │◄── ArgoCD app: k8s-dashboard-prod
 └──────────────────────────────────────────┘
```

---

## Branch Strategy

| App repo branch | Who pushes | Manifest branch updated | Image tag | Approval | Target namespace |
|:----------------|:-----------|:------------------------|:----------|:---------|:-----------------|
| `dev` | Developer daily work | `dev` | Build number (`:42`) | None — auto | `k8s-dashboard-dev` |
| `uat` | Merge from `dev` when ready for testing | `uat` | Build number (`:43`) | None — auto | `k8s-dashboard-uat` |
| `prod` | Merge from `uat` after UAT sign-off | `prod` | Release version (`:1.2.0`) | **Human approval required** | `k8s-dashboard` |

### Branch flow

```
dev ──► uat ──► prod
 │        │        │
 ▼        ▼        ▼
 :42      :43    :1.2.0
 auto     auto   approval
 dev ns   uat ns  prod ns
```

Developers work on `dev`. When a feature is ready for testing, merge to `uat`. When UAT passes, merge to `prod` — which triggers the approval gate email before deploying.

---

## Full Pipeline Flow

### dev / uat branches (automatic)

```
1.  git push to dev or uat
        │
2.  Jenkins detects push (webhook)
        │
3.  Checkout & Git Info
4.  Build Artifact          (go build)
5.  SonarQube SAST          (code analysis)
6.  Dependency Check        (govulncheck inside golang:1.24-alpine + OSV Scanner)
7.  Vuln Scan — Dockerfile  (Trivy + OPA policies + Gitleaks secrets)
8.  Docker Build & Push     (image tagged :BUILD_NUMBER → Harbor)
9.  Sign Image              (cosign → signature stored in Harbor)
10. Generate SBOM           (Syft → Dependency-Track)
11. Vuln Scan — App Image   (Trivy full image scan)
12. Publish Security Results (DefectDojo)
        │
13. k8s Manifest Update     ← auto, no approval
        │  updateK8sManifest detects BRANCH_NAME = dev → updates manifest dev branch
        │  updateK8sManifest detects BRANCH_NAME = uat → updates manifest uat branch
        ▼
14. Manifest repo branch updated (dev or uat)
        │
15. ArgoCD detects change (within 3 min)
        │
16. ArgoCD syncs → pod restarts with new image
        │
17. Email: sync succeeded / failed
```

### prod branch (approval gate)

```
1.  git push to prod (after UAT sign-off)
        │
2-12. Same build + scan stages as above
        │
13. Production Approval Gate
        │  Sends email: "Approve k8s-dashboard v1.2.0 → PRODUCTION?"
        │  Pipeline pauses up to 30 minutes
        │
        ├── Approved ──► update manifest prod branch with :1.2.0
        │                       │
        │               ArgoCD syncs → prod pod restarts
        │
        ├── Rejected ──► rejection email sent, build FAILED
        │
        └── Timeout  ──► timeout email sent, build ABORTED
```

---

## Manifest Repository Structure

```
k8s-dashboard-manifest.git
├── k8s/                       ← ArgoCD watches this path
│   ├── 00-namespace.yaml
│   ├── 01-configmap.yaml
│   ├── 02-deployment.yaml     ← image tag is updated here by Jenkins
│   ├── 03-service.yaml
│   ├── 04-httproute.yaml
│   ├── 05-serviceaccount.yaml
│   ├── 06-clusterrole.yaml
│   └── 07-clusterrolebinding.yaml
│
└── argocd/                    ← ArgoCD does NOT watch this path
    ├── k8s-dashboard-dev.yaml     ← apply once to register with ArgoCD
    ├── k8s-dashboard-uat.yaml
    └── k8s-dashboard-prod.yaml
```

**Important**: The `argocd/` folder is outside the `path: k8s` that ArgoCD syncs. It is purely for reference and version control — you `kubectl apply` these files once to register the application, then ArgoCD manages everything automatically.

---

## Jenkins Pipeline — Multi-Environment Behaviour

### Jenkinsfile environment block

Add these variables to your Jenkinsfile (per project):

```groovy
environment {
    // ... existing vars ...

    // Manifest repo branch names per environment.
    // These default to 'dev', 'uat', 'prod' if not set.
    // Only set these if you use different branch names.
    // K8S_MANIFEST_BRANCH      = 'dev'   // default for dev/main
    // K8S_MANIFEST_UAT_BRANCH  = 'uat'   // default for uat
    // K8S_MANIFEST_PROD_BRANCH = 'prod'  // default for prod

    // Release version (prod only) — read from VERSION file in repo root.
    // Bump before each prod release: echo "1.2.0" > VERSION
    RELEASE_VERSION = sh(script: "cat VERSION 2>/dev/null || echo ${env.APP_VERSION}", returnStdout: true).trim()
}
```

### How Jenkins knows which environment to target

Jenkins Multibranch Pipeline sets `BRANCH_NAME` automatically from the Git branch being built. The shared library functions read it:

| `BRANCH_NAME` in Jenkins | Manifest branch updated | Image tag used |
|:--------------------------|:------------------------|:---------------|
| `dev` or `main` | `dev` | `BUILD_NUMBER` |
| `uat` | `uat` | `BUILD_NUMBER` |
| `prod` | `prod` | `RELEASE_VERSION` (from `VERSION` file) |

> **Important**: Do not hardcode `BRANCH_NAME = 'main'` in the environment block for multi-environment projects. Remove it so Jenkins sets it automatically from the Git branch.

### Stage conditions in the Jenkinsfile

```groovy
// Approval gate — only on prod
stage('Production Approval') {
    when { branch 'prod' }
    steps {
        script {
            productionApproval(
                releaseVersion: env.RELEASE_VERSION,
                recipients:     env.NOTIFICATION_EMAIL,
                timeoutMinutes: 30
            )
        }
    }
}

// Regular manifest update — dev and uat only (prod handled by approval above)
stage('k8s Manifest Update') {
    when { not { branch 'prod' } }
    steps {
        script { k8sManifestScanAndUpdate() }
    }
}
```

---

## ArgoCD Application Setup

Each environment has its own ArgoCD `Application` resource. ArgoCD watches a specific branch of the manifest repo and automatically syncs whenever Jenkins pushes a change.

### k8s-dashboard-dev.yaml

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: k8s-dashboard-dev
  namespace: argocd
spec:
  project: default
  source:
    repoURL: http://192.168.15.85/kubernetes-manifest/k8s-dashboard-manifest.git
    targetRevision: dev           # watches the dev branch
    path: k8s
  destination:
    server: https://kubernetes.default.svc
    namespace: k8s-dashboard-dev  # isolated dev namespace
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

### k8s-dashboard-uat.yaml

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: k8s-dashboard-uat
  namespace: argocd
spec:
  project: default
  source:
    repoURL: http://192.168.15.85/kubernetes-manifest/k8s-dashboard-manifest.git
    targetRevision: uat           # watches the uat branch
    path: k8s
  destination:
    server: https://kubernetes.default.svc
    namespace: k8s-dashboard-uat  # isolated UAT namespace
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

### k8s-dashboard-prod.yaml

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: k8s-dashboard-prod
  namespace: argocd
spec:
  project: default
  source:
    repoURL: http://192.168.15.85/kubernetes-manifest/k8s-dashboard-manifest.git
    targetRevision: prod          # watches the prod branch
    path: k8s
  destination:
    server: https://kubernetes.default.svc   # or prod cluster URL — see below
    namespace: k8s-dashboard
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

---

## Dev + UAT on the Same Cluster (Namespace Isolation)

Dev and UAT run on the same physical cluster but are fully isolated by Kubernetes namespace. ArgoCD deploys each environment to its own namespace simply by setting a different `namespace` in `destination`.

### What this means

```
Same cluster: dev-mastr
┌─────────────────────────────────────────────────┐
│                                                 │
│  namespace: k8s-dashboard-dev                   │
│    pod: k8s-dashboard (image :42)               │
│    service: k8s-dashboard (NodePort)            │
│                                                 │
│  namespace: k8s-dashboard-uat                   │
│    pod: k8s-dashboard (image :43)               │
│    service: k8s-dashboard (NodePort)            │
│                                                 │
│  namespace: k8s-dashboard  (prod on same cluster│
│    pod: k8s-dashboard (image :1.2.0)            │  until prod cluster is ready
│    service: k8s-dashboard (NodePort)            │
│                                                 │
└─────────────────────────────────────────────────┘
```

### Network isolation with NetworkPolicy

Add a `NetworkPolicy` to each namespace to prevent cross-namespace traffic. Create this in each environment's manifest files:

```yaml
# k8s/08-networkpolicy.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: deny-cross-namespace
  namespace: k8s-dashboard-dev     # change per environment
spec:
  podSelector: {}                  # applies to all pods in this namespace
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: k8s-dashboard-dev   # only from same namespace
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: istio-system        # allow gateway/ingress
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: k8s-dashboard-dev
    - ports:
        - port: 53                # allow DNS
          protocol: UDP
        - port: 443               # allow HTTPS to Harbor, DefectDojo, etc.
          protocol: TCP
```

### Namespace labels (required for NetworkPolicy selectors)

```bash
kubectl label namespace k8s-dashboard-dev kubernetes.io/metadata.name=k8s-dashboard-dev
kubectl label namespace k8s-dashboard-uat kubernetes.io/metadata.name=k8s-dashboard-uat
kubectl label namespace k8s-dashboard     kubernetes.io/metadata.name=k8s-dashboard
```

> Kubernetes 1.21+ sets `kubernetes.io/metadata.name` automatically. Verify with:
> `kubectl get namespace k8s-dashboard-dev --show-labels`

---

## Separate Production Cluster — How to Add

When you have a dedicated production cluster, follow these steps.

### Step 1 — Get the prod cluster API URL

On the **prod cluster control plane** node:

```bash
kubectl cluster-info
# Output:
# Kubernetes control plane is running at https://192.168.X.X:6443
```

Or from your kubeconfig:

```bash
kubectl config view --minify | grep server
# server: https://192.168.X.X:6443
```

### Step 2 — Register the prod cluster with ArgoCD

Run this on the machine that has both clusters in its kubeconfig (or on the ArgoCD server):

```bash
# List available kubeconfig contexts
kubectl config get-contexts

# Register the prod cluster — replace 'prod-cluster-context' with your context name
argocd cluster add prod-cluster-context --name prod-cluster

# Verify it was added
argocd cluster list
```

ArgoCD will output the server URL after registering. It looks like:
```
SERVER                        NAME          STATUS
https://192.168.X.X:6443      prod-cluster  Successful
https://kubernetes.default.svc  in-cluster  Successful
```

### Step 3 — Update k8s-dashboard-prod.yaml

Change the `destination.server` in `argocd/k8s-dashboard-prod.yaml`:

```yaml
destination:
  server: https://192.168.X.X:6443   # prod cluster API URL from argocd cluster list
  namespace: k8s-dashboard
```

Re-apply:

```bash
kubectl apply -f argocd/k8s-dashboard-prod.yaml
```

ArgoCD now deploys to the prod cluster while managing the operation from the dev cluster (hub-and-spoke model).

### What if the prod cluster is on a different network?

ArgoCD (running on dev cluster) must have network access to the prod cluster API port (`:6443`). If they are on different networks:
- Open firewall port `6443` from the dev cluster to the prod cluster
- Or set up a VPN tunnel between the two clusters
- Or run ArgoCD on the prod cluster itself and use `https://kubernetes.default.svc`

---

## Applying ArgoCD Applications

### One-time setup per project

After creating the Application YAML files and committing them to the manifest repo, apply them once:

```bash
# Add the manifest repo to ArgoCD (one-time, if not already added)
argocd repo add http://192.168.15.85/kubernetes-manifest/k8s-dashboard-manifest.git \
  --username <gitlab-username> \
  --password <gitlab-password> \
  --insecure-skip-server-verification

# Apply all three environments
kubectl apply -f argocd/k8s-dashboard-dev.yaml
kubectl apply -f argocd/k8s-dashboard-uat.yaml
kubectl apply -f argocd/k8s-dashboard-prod.yaml

# Verify
argocd app list
```

After this, ArgoCD manages the applications forever — no further `kubectl apply` needed unless you change the Application definition itself (e.g. change the cluster URL or branch name).

### Verify sync status

```bash
argocd app get k8s-dashboard-dev
argocd app get k8s-dashboard-uat
argocd app get k8s-dashboard-prod
```

### Force a manual sync

```bash
argocd app sync k8s-dashboard-dev
```

---

## Replicating for a New Project

For every new service onboarded to the pipeline, repeat these steps:

### 1. Manifest repo

Create a new manifest repo in GitLab:
```
http://192.168.15.85/kubernetes-manifest/<service-name>-manifest.git
```

Inside the repo, create the same structure:
```
k8s/
  00-namespace.yaml
  01-configmap.yaml
  02-deployment.yaml
  03-service.yaml
  ...
argocd/
  <service-name>-dev.yaml
  <service-name>-uat.yaml
  <service-name>-prod.yaml
```

### 2. ArgoCD Application files

Copy from k8s-dashboard and change **4 values** per file:

| Field | What to change |
|:------|:---------------|
| `metadata.name` | `<service-name>-dev` / `-uat` / `-prod` |
| `source.repoURL` | new manifest repo URL |
| `source.targetRevision` | `dev` / `uat` / `prod` |
| `destination.namespace` | `<service-name>-dev` / `-uat` / `<service-name>` |

### 3. Jenkinsfile

Copy from k8s-dashboard Jenkinsfile and change the `environment {}` block:
- `PROJECT_NAME`, `IMAGE_NAME`, `HARBOR_PROJECT`
- `GIT_REPO_URL`
- `K8S_MANIFEST_REPO_URL`
- `DEFECTDOJO_ENGAGEMENT_ID` (create a new engagement in DefectDojo first)
- Remove `BRANCH_NAME = 'main'` — Jenkins sets it automatically

### 4. Apply and go

```bash
kubectl apply -f argocd/<service-name>-dev.yaml
kubectl apply -f argocd/<service-name>-uat.yaml
kubectl apply -f argocd/<service-name>-prod.yaml
```

From this point the pipeline is fully automated — every push to `dev`, `uat`, or `prod` flows through Jenkins, into Harbor, into the manifest repo, and ArgoCD deploys it to the right namespace automatically.

---

## Summary

| Question | Answer |
|:---------|:-------|
| How does ArgoCD know which branch to watch? | `targetRevision` in the Application YAML |
| How does ArgoCD know which cluster to deploy to? | `destination.server` — `kubernetes.default.svc` = same cluster, or API URL for external |
| How does Jenkins know which manifest branch to update? | Reads `BRANCH_NAME` (set by Jenkins Multibranch Pipeline automatically) |
| Do I need to merge between manifest branches? | No — Jenkins pushes directly to the right branch |
| Does the `argocd/` folder get deployed to Kubernetes? | No — ArgoCD only watches `path: k8s`, not `path: argocd` |
| Where do I get the prod cluster API URL? | `kubectl cluster-info` on the prod control plane, or `argocd cluster list` after registering |
| Do I need to apply the ArgoCD Application YAML every time? | No — apply once. ArgoCD watches forever after that |
