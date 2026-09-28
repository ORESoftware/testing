import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const nix = await readFile("deploy/baremetal/nixos/bmscl-lifecycle-agent.nix", "utf8");
const docs = await readFile("deploy/baremetal/process-lifecycle.md", "utf8");
const readme = await readFile("deploy/baremetal/README.md", "utf8");

for (const required of [
  'controlGroup = "beamscale-lifecycle-control"',
  'stateRoot = "/var/lib/beamscale/lifecycle"',
  'compatibilityCheckpointRoot = "/var/lib/beamscale/lifecycle-disabled-checkpoints"',
  'managedCgroupRoot = "/sys/fs/cgroup/beamscale-workloads.slice"',
  'socketRoot = "/run/beamscale-lifecycle"',
  'productSocket = "${socketRoot}/product/control.sock"',
  'hostControlSocket = "${socketRoot}/host/control.sock"',
  'productSocketDirectory = builtins.dirOf productSocket',
  'hostControlSocketDirectory = builtins.dirOf hostControlSocket',
  'productUnit = "${cfg.productService}.service"',
  'productService = lib.mkOption',
  'controlSocketOwner = lib.mkOption',
  '"d ${socketRoot} 0755 root root -"',
  '"d ${productSocketDirectory} 2770 ${cfg.controlSocketOwner} ${controlGroup} -"',
  '"d ${hostControlSocketDirectory} 0700 root root -"',
  'systemd.services.${cfg.productService}.environment',
  'BMSCL_LIFECYCLE_SOCKET = productSocket',
  'systemd.services.${cfg.productService}.serviceConfig.ReadWritePaths',
  'lib.mkAfter [ productSocketDirectory ]',
  'systemd.services.${cfg.productService}.serviceConfig.InaccessiblePaths',
  'lib.mkAfter [ hostControlSocketDirectory ]',
  'ORES_PROCESS_LIFECYCLE_PRODUCT = "beamscale"',
  'ORES_PROCESS_LIFECYCLE_CHECKPOINT_ROOT = compatibilityCheckpointRoot',
  'ORES_PROCESS_LIFECYCLE_PRODUCT_SOCKET = productSocket',
  'ORES_PROCESS_LIFECYCLE_HOST_CONTROL_SOCKET = hostControlSocket',
  'ORES_PROCESS_LIFECYCLE_LEASE_BACKEND = "cloudflare-do"',
  'ORES_PROCESS_LIFECYCLE_HIBERNATE_ENABLED = "false"',
  'ORES_PROCESS_LIFECYCLE_EFFECTS_ENABLED = "false"',
  '"${cfg.package}/bin/${cfg.binary} preflight"',
  '"${cfg.package}/bin/${cfg.binary} probe-product"',
  'SupplementaryGroups = [ controlGroup ]',
  'CapabilityBoundingSet = [ ]',
  'AmbientCapabilities = [ ]',
  'ProtectControlGroups = true',
  'RestrictAddressFamilies = [ "AF_UNIX" ]',
  'ReadOnlyPaths = [',
  'hostControlSocketDirectory',
  'ReadWritePaths = [ stateRoot ]',
]) {
  assert.ok(nix.includes(required), `missing observe-only lifecycle invariant: ${required}`);
}

for (const forbidden of [
  'productSocket = "/run/beamscale-lifecycle/control.sock"',
  'hostControlSocket = "/run/beamscale-lifecycle/host-control.sock"',
  'socketDirectory = builtins.dirOf productSocket',
  '"d ${productSocketDirectory} 2770 root',
  '"d ${hostControlSocketDirectory} 2770',
  "leaseProvider = lib.mkOption",
  "leaseEndpoint = lib.mkOption",
  "credentialName = lib.mkOption",
  "credentialFiles = lib.mkOption",
  "LoadCredential",
  "ORES_PROCESS_LIFECYCLE_LEASE_PROVIDER",
  "ORES_PROCESS_LIFECYCLE_LEASE_ENDPOINT",
  "ORES_PROCESS_LIFECYCLE_LEASE_KEY_PREFIX",
  "ORES_PROCESS_LIFECYCLE_CREDENTIAL_NAME",
  'ORES_PROCESS_LIFECYCLE_EFFECTS = "freeze_thaw"',
  "CAP_DAC_OVERRIDE",
  "CAP_SYS_ADMIN",
  "CAP_SYS_PTRACE",
  "ProtectControlGroups = false",
  'RestrictAddressFamilies = [ "AF_UNIX" "AF_INET" "AF_INET6" ]',
  "managedCgroupRoot\n        ];",
]) {
  assert.ok(!nix.includes(forbidden), `premature or weakened lifecycle authority: ${forbidden}`);
}

assert.ok(
  /requires = \[[\s\S]*"beamscale-workloads\.slice"[\s\S]*productUnit[\s\S]*\];/.test(nix),
  "lifecycle observer must require the configured BeamScale product service",
);
assert.ok(
  /after = \[[\s\S]*"beamscale-workloads\.slice"[\s\S]*productUnit[\s\S]*\];/.test(nix),
  "lifecycle observer must start after the configured BeamScale product service",
);
assert.ok(
  /ReadOnlyPaths = \[[\s\S]*"\/sys\/fs\/cgroup"[\s\S]*compatibilityCheckpointRoot[\s\S]*hostControlSocketDirectory[\s\S]*\];/.test(nix),
  "observe-only agent must see cgroups, compatibility checkpoint root, and trusted socket path read-only",
);
assert.ok(
  /ReadWritePaths = \[ stateRoot \];/.test(nix),
  "observe-only agent may write only its lifecycle record root",
);

for (const required of [
  "ORESoftware/ores-locks-and-leases",
  "process-lifecycle/beamscale/<cluster>/<workload>",
  "Node identity is deliberately excluded",
  "fresh logical request",
  "fencing token is newer",
  "separately reviewed checkpoint helper",
  "/run/beamscale-lifecycle/product/control.sock",
  "/run/beamscale-lifecycle/host/control.sock",
  "must not share a writable parent",
]) {
  assert.ok(docs.includes(required), `lifecycle docs missing: ${required}`);
}

assert.ok(
  !docs.includes("beamscale/runtime-lifecycle/<environment>/<region>/<runtime-id>"),
  "legacy BeamScale-specific lifecycle lock key must not diverge from the shared adapter",
);

for (const required of [
  "observe/preflight-only",
  "probe-product",
  "BMSCL_LIFECYCLE_SOCKET",
  "SO_PEERCRED",
  "beamscale-lifecycle-control",
  "effects remain disabled",
  "freeze/thaw",
  "Real RAM",
  "/run/beamscale-lifecycle/product/control.sock",
  "/run/beamscale-lifecycle/host/control.sock",
  "InaccessiblePaths",
]) {
  assert.ok(readme.includes(required), `baremetal README missing: ${required}`);
}

console.log("BeamScale lifecycle agent contract: bootable observe-only boundary ok");
