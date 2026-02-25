Enabling Content Security Policy configuration
Content Security Policy is a security mechanism that can reduce or eliminate the impact of web security vulnerabilities like cross-site-scripting (XSS).

Most popular Jenkins plugins are compatible with Jenkins's default rule set, but not everything currently installed may be. If you choose to enforce Content Security Policy and it causes the Jenkins UI to break, you can start Jenkins with the Java system property jenkins.security.csp.CspHeader.headerName set to Content-Security-Policy-Report-Only to disable protections again.

For resources on determining whether your current setup is compatible with Content Security Policy enforcement, visit the documentation website.