# Hardened UntrustedActor execution with GraalWasm

Tracking issue: #108

This branch prototypes the Oreslang execution path for adversarial guest code:

```text
trusted Java / Oreslang supervisor
              │
              │ capability API only
              ▼
┌─────────────────────────────────┐
│ Graal native-image isolate      │
│ SandboxPolicy.UNTRUSTED         │
│                                 │
│   GraalWasm                     │
│       ↓                         │
│   untrusted Oreslang/Wasm       │
│                                 │
│ bounded memory                  │
│ bounded CPU                     │
│ bounded threads                 │
│ restricted host access          │
└─────────────────────────────────┘
```

## Design requirements

- Untrusted Oreslang code must not execute as an ordinary JVM thread or unrestricted polyglot `Context`.
- The guest receives explicit capabilities only; it does not receive ambient JVM authority.
- Direct filesystem, raw socket, native, process-creation, arbitrary reflection, and unrestricted host-object access are forbidden.
- CPU, memory, thread, output, and host↔guest transfer limits must be explicit and covered by regression tests.
- The trusted supervisor owns lifecycle, cancellation, observability, capability issuance, and cleanup.
- Support an optional stronger mode using `engine.IsolateMode=external` plus an OS-level sandbox.
- Do not claim equivalence with Cloudflare Workers production security unless the full defense-in-depth stack is comparable.

## Intended lowering

```text
Oreslang source
      ↓
typed Oreslang IR
      ↓
Wasm module
      ↓
GraalWasm
      ↓
SandboxPolicy.UNTRUSTED
      ↓
dedicated isolate
      ↓
optional external process + OS sandbox
```

## Initial implementation milestones

1. Pin a GraalVM version supporting `wasm-isolate` and `SandboxPolicy.UNTRUSTED`.
2. Add a minimal Java supervisor that loads a trivial Wasm module through GraalWasm.
3. Configure all mandatory resource limits for the pinned GraalVM version.
4. Add capability-only host bindings.
5. Add hostile fixtures covering CPU, memory, threads, output, host access, filesystem, network, native/process creation, and guest crashes.
6. Add external isolate mode and verify supervisor survival across guest failure.
7. Add Linux sandboxing research/prototype for namespaces, seccomp, and cgroups.

This document is intentionally a scaffold for the implementation work in issue #108.
