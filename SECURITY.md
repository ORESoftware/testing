# Security policy

## Supported versions

Only the latest `main` branch is supported before the first tagged release.

## Reporting a vulnerability

Do not open a public issue for a suspected credential leak, remote-authentication bypass, cross-scope data exposure, parser denial of service, or script-injection defect. Use GitHub's private security advisory flow once the repository is published, or contact the organization owner privately.

Include the affected commit, configuration, reproduction steps, impact, and any suggested mitigation. Do not include real provider credentials or private agent payloads.

## Deployment assumptions

- Loopback listeners are the default.
- Remote listeners require a strong shared token and protected read APIs unless the operator explicitly enables the isolated-network override.
- Shared-token authentication is an MVP mechanism, not multi-tenant identity.
- HTTP/WebSocket confidentiality requires TLS at a trusted reverse proxy.
- TCP and UDP require a private network, VPN, mTLS sidecar, or equivalent confidentiality layer.
- UDP acknowledgements are advisory and UDP cannot submit high-authority state transitions.
- Event payloads are untrusted input and must not contain credentials or hidden chain-of-thought.
