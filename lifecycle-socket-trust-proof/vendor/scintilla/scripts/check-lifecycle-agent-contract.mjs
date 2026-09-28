import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const nix = await readFile("fleet/nixos/scintilla-lifecycle-agent.nix", "utf8");
const readme = await readFile("deploy/baremetal/README.md", "utf8");

for (const required of [
  'controlGroup = "scintilla-lifecycle-control"',
  'managedCgroupRoot = "/sys/fs/cgroup/scintilla-workloads.slice"',
  'socketRoot = "/run/scintilla-lifecycle"',
  'productSocket = "${socketRoot}/product/control.sock"',
  'hostControlSocket = "${socketRoot}/host/control.sock"',
  'productSocketDirectory = builtins.dirOf productSocket',
  'hostControlSocketDirectory = builtins.dirOf hostControlSocket',
  '"d ${socketRoot} 0755 root root -"',
  '"d ${productSocketDirectory} 2770 ${runtimeCfg.runtimeUser} ${controlGroup} -"',
  '"d ${hostControlSocketDirectory} 0700 root root -"',
  'SCINTILLA_LIFECYCLE_SOCKET = productSocket',
  'SCINTILLA_LIFECYCLE_AGENT_UID = "0"',
  'SCINTILLA_LIFECYCLE_AGENT_GID = "0"',
  'systemd.services.scintilla-runtime.serviceConfig.ReadWritePaths',
  'lib.mkAfter [ productSocketDirectory ]',
  'systemd.services.scintilla-runtime.serviceConfig.InaccessiblePaths',
  'lib.mkAfter [ hostControlSocketDirectory ]',
  'ORES_PROCESS_LIFECYCLE_PRODUCT = "scintilla-run"',
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
  'hostControlSocketDirectory',
  'ReadWritePaths = [ stateRoot ]',
]) {
  assert.ok(nix.includes(required), `missing Scintilla lifecycle invariant: ${required}`);
}

assert.match(
  nix,
  /requires = \[[\s\S]*"scintilla-runtime\.service"[\s\S]*"scintilla-workloads\.slice"[\s\S]*\];/,
  "lifecycle observer must require runtime and workload slice",
);
assert.match(
  nix,
  /after = \[[\s\S]*"scintilla-runtime\.service"[\s\S]*"scintilla-workloads\.slice"[\s\S]*\];/,
  "lifecycle observer must start after runtime and workload slice",
);
assert.match(
  nix,
  /ReadOnlyPaths = \[[\s\S]*"\/sys\/fs\/cgroup"[\s\S]*compatibilityCheckpointRoot[\s\S]*hostControlSocketDirectory[\s\S]*\];/,
  "observe-only agent must see cgroups, checkpoint compatibility root, and trusted socket directory read-only",
);

for (const forbidden of [
  'productSocket = "/run/scintilla-lifecycle/control.sock"',
  'hostControlSocket = "/run/scintilla-lifecycle/host-control.sock"',
  'socketDirectory = builtins.dirOf productSocket',
  '"d ${hostControlSocketDirectory} 2770',
  'wants = [ "scintilla-runtime.service" ]',
  "LoadCredential",
  "CAP_DAC_OVERRIDE",
  "CAP_SYS_ADMIN",
  "CAP_SYS_PTRACE",
  "ProtectControlGroups = false",
  'RestrictAddressFamilies = [ "AF_UNIX" "AF_INET" "AF_INET6" ]',
  'ORES_PROCESS_LIFECYCLE_EFFECTS_ENABLED = "true"',
  'ORES_PROCESS_LIFECYCLE_HIBERNATE_ENABLED = "true"',
]) {
  assert.ok(!nix.includes(forbidden), `premature or weakened Scintilla lifecycle authority: ${forbidden}`);
}

for (const required of [
  "/run/scintilla-lifecycle/product/control.sock",
  "/run/scintilla-lifecycle/host/control.sock",
  "must not share a writable parent",
  "InaccessiblePaths",
  "effects remain disabled",
]) {
  assert.ok(readme.includes(required), `bare-process lifecycle docs missing: ${required}`);
}

console.log("Scintilla lifecycle observer contract: fail-closed product/host trust split ok");
