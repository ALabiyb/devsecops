package main
# === Deny resources without namespace (except Namespace itself) ===
violation contains msg if {
    input.kind != "Namespace"
    not input.metadata.namespace
    msg := sprintf("Resource %s '%s' must specify a namespace", [input.kind, input.metadata.name])
}

# === Deny explicit runAsUser: 0 (running as root) ===
violation contains msg if {
    container := input.spec.template.spec.containers[_]
    container.securityContext.runAsUser == 0
    msg := sprintf("Container '%s' in %s '%s' explicitly runs as root (runAsUser: 0)", [
        container.name,
        input.kind,
        input.metadata.name
    ])
}

# === Require runAsNonRoot: true (best practice - catches missing or false) ===
violation contains msg if {
    container := input.spec.template.spec.containers[_]
    security := container.securityContext
    not security.runAsNonRoot == true
    msg := sprintf("Container '%s' in %s '%s' must set securityContext.runAsNonRoot: true", [
        container.name,
        input.kind,
        input.metadata.name
    ])
}

# === Deny privileged containers ===
violation contains msg if {
    container := input.spec.template.spec.containers[_]
    container.securityContext.privileged == true
    msg := sprintf("Container '%s' in %s '%s' is privileged", [
        container.name,
        input.kind,
        input.metadata.name
    ])
}

# === Deny privilege escalation ===
violation contains msg if {
    container := input.spec.template.spec.containers[_]
    container.securityContext.allowPrivilegeEscalation == true
    msg := sprintf("Container '%s' in %s '%s' allows privilege escalation", [
        container.name,
        input.kind,
        input.metadata.name
    ])
}

# === Require readOnlyRootFilesystem (except for databases that need write access) ===
violation contains msg if {
    container := input.spec.template.spec.containers[_]
    # Exclude known stateful/database containers
    not container.name in {"postgres", "mysql", "mongodb", "redis"}
    not container.securityContext.readOnlyRootFilesystem == true
    msg := sprintf("Container '%s' in %s '%s' must have readOnlyRootFilesystem: true", [
        container.name,
        input.kind,
        input.metadata.name
    ])
}

# === Require readOnlyRootFilesystem ===
#violation contains msg if {
#    container := input.spec.template.spec.containers[_]
#    not container.securityContext.readOnlyRootFilesystem == true
#    msg := sprintf("Container '%s' in %s '%s' must have readOnlyRootFilesystem: true", [
#        container.name,
#        input.kind,
#        input.metadata.name
#    ])
#}

# === Require resource limits ===
violation contains msg if {
    container := input.spec.template.spec.containers[_]
    not container.resources.limits
    msg := sprintf("Container '%s' in %s '%s' must define resource limits", [
        container.name,
        input.kind,
        input.metadata.name
    ])
}

# === Deny hostPID and hostIPC ===
violation contains msg if {
    input.spec.template.spec.hostPID == true
    msg := sprintf("Host PID sharing is not allowed in %s '%s'", [input.kind, input.metadata.name])
}

violation contains msg if {
    input.spec.template.spec.hostIPC == true
    msg := sprintf("Host IPC sharing is not allowed in %s '%s'", [input.kind, input.metadata.name])
}

# === Deny dangerous hostPath mounts (/proc, /sys, /) ===
violation contains msg if {
    volume := input.spec.template.spec.volumes[_]
    volume.hostPath
    volume.hostPath.path in {"/proc", "/sys", "/"}
    msg := sprintf("Mounting '%s' via hostPath is not allowed in %s '%s'", [
        volume.hostPath.path,
        input.kind,
        input.metadata.name
    ])
}

# === Deny :latest tag on container images ===
violation contains msg if {
    container := input.spec.template.spec.containers[_]
    endswith(container.image, ":latest")
    msg := sprintf("Container '%s' in %s '%s' uses ':latest' tag - pin to specific version", [
        container.name,
        input.kind,
        input.metadata.name
    ])
}

# === Service must be NodePort (your custom rule) ===
violation contains msg if {
    input.kind == "Service"
    input.spec.type != "NodePort"
    msg := sprintf("Service '%s' must use type NodePort (current: %s)", [
        input.metadata.name,
        input.spec.type
    ])
}