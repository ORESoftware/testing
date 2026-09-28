# Scintilla bare-process fleet

The bare-process backend is the high-density native execution path for Scintilla. It supports admitted native binaries and language runtimes without Kubernetes, OCI containers, or microVMs.

## Fleet

Use 3-9 NixOS hosts across at least two regions/failure domains. Provision cloud primitives independently from host configuration. NixOS + Colmena owns reproducible host state and rollout; it is not the source of application release identity.

The regional design is active/active where the application permits it. Do not create a WAN-stretched host quorum for execution scheduling. Region-local schedulers should be able to keep serving admitted releases when the peer region is unavailable.

## Launch invariant

Every tenant-influenced native execution goes through `ORESoftware/ores-proc-isolation-cli` or its audited library boundary. The checked-in `.ores-proc-isolation.yaml` is the policy authority for the first slice.

A trusted `scintilla-native-runner` should perform only admission/translation needed to launch the already-selected immutable artifact/runtime. It must not become a generic unsandboxed shell. Before launch it verifies:

1. release/function identity and artifact digest;
2. runtime/architecture/entrypoint compatibility;
3. capability/resource policy digest;
4. process-isolation tool and policy digest;
5. requested arguments/environment against the admitted manifest;
6. placement epoch/generation so stale schedulers cannot mutate replacement work.

Failure of any check is terminal admission refusal.

## Host lifecycle trust boundary

`scintilla-runtime` owns only the cooperative quiesce/resume bridge. The shared root lifecycle agent and future host/control-plane v2 server own lifecycle fencing, trusted demand/placement/process observation, and post-wake admission.

The Unix sockets must not share a writable parent:

```text
/run/scintilla-lifecycle/              root:root 0755
  product/                             <runtime-user>:scintilla-lifecycle-control 2770
    control.sock                       cooperative runtime bridge
  host/                                root:root 0700
    control.sock                       trusted host/control-plane v2
```

The exact paths are:

```text
/run/scintilla-lifecycle/product/control.sock
/run/scintilla-lifecycle/host/control.sock
```

This is a filesystem authority boundary. A process with write permission on a Unix-socket parent directory can unlink or replace the socket entry even when it does not own the socket inode. The runtime therefore receives `ReadWritePaths` only for the `product/` directory and `InaccessiblePaths` for `host/`. The root lifecycle agent receives the trusted host-control path through `ORES_PROCESS_LIFECYCLE_HOST_CONTROL_SOCKET` but does not create or own trusted scheduler state itself.

The lifecycle integration remains observe/preflight-only: hibernation and process effects remain disabled. Do not enable freeze/thaw until a real host-control v2 server derives workload assignment, runtime/placement epochs, PID + process-start identity, managed cgroup, policy digest, queue/in-flight state and routability from trusted host/scheduler state, then re-attests the exact identity after wake under a current distributed fence.

The cooperative runtime bridge continues to authenticate the lifecycle agent with kernel peer credentials. That protects the cooperative command channel but does not elevate runtime-provided queue or readiness data into trusted scheduler authority.

## Language runtimes

Interpreted/JIT runtimes remain supported: the sandbox can launch an admitted interpreter/runtime executable plus immutable user payload. Runtime installation belongs to the immutable NixOS host generation or a content-addressed runtime store. A request must not download and execute a mutable runtime implicitly.

Examples include Rust/C/C++/Go native binaries, Node.js, Python, JVM, .NET, Erlang/BEAM, Elixir and Gleam, subject to explicit runtime profiles. The same isolation rules apply regardless of language.

## Container workloads

OCI workloads are not executed by this backend. They remain supported by Scintilla through Kubernetes or a separately admitted container-host carrier. Scheduler admission must not silently unpack an OCI image and treat it as an equivalent bare process because image filesystem/user/entrypoint semantics differ.

## Host hardening

The isolation policy v1 is intentionally not overclaimed. NixOS/systemd/cgroup controls provide independent memory, PID/process, CPU, device and service-identity restrictions. Cloud firewall/security-group policy independently blocks metadata/private control surfaces not intended for workers.

Prefer a dedicated execution user, immutable/read-only runtime paths, no operator home/source mounts, no inherited credentials, ephemeral per-invocation working directories and explicit cleanup. Secrets are capabilities/references resolved by trusted infrastructure, never ambient host environment.

## Rollout

Canary host -> regional subset -> remaining region A -> region B. Preserve enough old-generation capacity for rollback. A rollback selects the prior admitted host/runtime/release generation; no application rebuild is required.
