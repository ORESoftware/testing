{ config, lib, ... }:

let
  cfg = config.services.scintillaLifecycleAgent;
  runtimeCfg = config.services.scintillaRuntime;
  controlGroup = "scintilla-lifecycle-control";
  stateRoot = "/var/lib/scintilla/lifecycle-agent";
  compatibilityCheckpointRoot = "/var/lib/scintilla/lifecycle-disabled-checkpoints";
  managedCgroupRoot = "/sys/fs/cgroup/scintilla-workloads.slice";
  socketRoot = "/run/scintilla-lifecycle";
  productSocket = "${socketRoot}/product/control.sock";
  productSocketDirectory = builtins.dirOf productSocket;
  hostControlSocket = "${socketRoot}/host/control.sock";
  hostControlSocketDirectory = builtins.dirOf hostControlSocket;
in
{
  options.services.scintillaLifecycleAgent = {
    enable = lib.mkEnableOption "external Scintilla host lifecycle observer/preflight agent";

    package = lib.mkOption {
      type = lib.types.package;
      description = "Pinned package containing ores-process-lifecycle-agent from ores-otel/ores-otel-sidecar.rs.";
    };

    binary = lib.mkOption {
      type = lib.types.str;
      default = "ores-process-lifecycle-agent";
    };

    cluster = lib.mkOption {
      type = lib.types.str;
      description = "Stable Scintilla fleet/cell identity used by the shared lifecycle runtime.";
    };

    node = lib.mkOption {
      type = lib.types.str;
      default = config.networking.hostName;
      description = "Stable current host/controller identity.";
    };

    reconcileSeconds = lib.mkOption {
      type = lib.types.ints.positive;
      default = 15;
      description = "Observe-only reconciliation cadence while the trusted mutation runtime remains disabled.";
    };
  };

  config = lib.mkIf cfg.enable {
    assertions = [
      {
        assertion = runtimeCfg.enable;
        message = "Scintilla lifecycle agent requires services.scintillaRuntime.enable";
      }
      {
        assertion =
          builtins.match "^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$" cfg.cluster != null;
        message = "Scintilla lifecycle cluster must be a safe shared-agent identity segment";
      }
      {
        assertion =
          builtins.match "^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$" cfg.node != null;
        message = "Scintilla lifecycle node must be a safe shared-agent identity segment";
      }
    ];

    users.groups.${controlGroup} = {};
    users.users.${runtimeCfg.runtimeUser}.extraGroups = [ controlGroup ];

    systemd.slices."scintilla-workloads" = {
      description = "Scintilla suspendable workload processes";
      sliceConfig = {
        CPUAccounting = true;
        MemoryAccounting = true;
        TasksAccounting = true;
      };
    };

    systemd.tmpfiles.rules = [
      "d ${stateRoot} 0700 root root -"
      # Current shared-agent preflight requires a checkpoint directory even
      # when hibernation is disabled. Keep a separate read-only compatibility
      # directory rather than granting the generic agent checkpoint authority.
      "d ${compatibilityCheckpointRoot} 0500 root root -"
      # Product and trusted host-control sockets must never share a writable
      # parent. Directory write permission is sufficient to unlink/squat a Unix
      # socket entry even when the socket inode itself is root-owned.
      "d ${socketRoot} 0755 root root -"
      "d ${productSocketDirectory} 2770 ${runtimeCfg.runtimeUser} ${controlGroup} -"
      "d ${hostControlSocketDirectory} 0700 root root -"
    ];

    # The product runtime owns cooperative quiesce/resume only. It receives no
    # distributed lease, fencing, queue-authority, or checkpoint credentials.
    systemd.services.scintilla-runtime.environment = {
      SCINTILLA_LIFECYCLE_SOCKET = productSocket;
      SCINTILLA_LIFECYCLE_AGENT_UID = "0";
      SCINTILLA_LIFECYCLE_AGENT_GID = "0";
    };
    systemd.services.scintilla-runtime.serviceConfig.ReadWritePaths =
      lib.mkAfter [ productSocketDirectory ];
    systemd.services.scintilla-runtime.serviceConfig.InaccessiblePaths =
      lib.mkAfter [ hostControlSocketDirectory ];

    systemd.services.scintilla-lifecycle-agent = {
      description = "Scintilla external host lifecycle observer/preflight agent";
      wantedBy = [ "multi-user.target" ];
      requires = [
        "scintilla-runtime.service"
        "scintilla-workloads.slice"
      ];
      after = [
        "scintilla-runtime.service"
        "scintilla-workloads.slice"
      ];

      # Match the executable contract that actually ships today. The trusted
      # lease/demand/process adapters remain required before mutation mode may be
      # enabled, but observe mode must not start against a missing product bridge.
      environment = {
        ORES_PROCESS_LIFECYCLE_PRODUCT = "scintilla-run";
        ORES_PROCESS_LIFECYCLE_CLUSTER = cfg.cluster;
        ORES_PROCESS_LIFECYCLE_NODE = cfg.node;
        ORES_PROCESS_LIFECYCLE_STATE_ROOT = stateRoot;
        ORES_PROCESS_LIFECYCLE_CHECKPOINT_ROOT = compatibilityCheckpointRoot;
        ORES_PROCESS_LIFECYCLE_CGROUP_ROOT = managedCgroupRoot;
        ORES_PROCESS_LIFECYCLE_PRODUCT_SOCKET = productSocket;
        ORES_PROCESS_LIFECYCLE_HOST_CONTROL_SOCKET = hostControlSocket;
        ORES_PROCESS_LIFECYCLE_RECONCILE_SECONDS = toString cfg.reconcileSeconds;
        ORES_PROCESS_LIFECYCLE_LEASE_BACKEND = "cloudflare-do";
        ORES_PROCESS_LIFECYCLE_HIBERNATE_ENABLED = "false";
        ORES_PROCESS_LIFECYCLE_EFFECTS_ENABLED = "false";
      };

      serviceConfig = {
        User = "root";
        Group = "root";
        SupplementaryGroups = [ controlGroup ];
        ExecStartPre = [
          "${cfg.package}/bin/${cfg.binary} preflight"
          "${cfg.package}/bin/${cfg.binary} probe-product"
        ];
        ExecStart = "${cfg.package}/bin/${cfg.binary}";
        Restart = "always";
        RestartSec = "2s";
        UMask = "0077";

        # Observe/preflight-only means no cgroup write, checkpoint, ptrace, mount,
        # DAC-bypass, or network authority is needed. Do not broaden this until
        # exact-head live mutation proof exists for the shared agent.
        NoNewPrivileges = true;
        CapabilityBoundingSet = [ ];
        AmbientCapabilities = [ ];
        PrivateDevices = true;
        PrivateTmp = true;
        ProtectClock = true;
        ProtectControlGroups = true;
        ProtectHome = true;
        ProtectHostname = true;
        ProtectKernelLogs = true;
        ProtectKernelModules = true;
        ProtectKernelTunables = true;
        ProtectProc = "invisible";
        ProtectSystem = "strict";
        ProcSubset = "pid";
        RestrictAddressFamilies = [ "AF_UNIX" ];
        RestrictNamespaces = true;
        RestrictRealtime = true;
        RestrictSUIDSGID = true;
        LockPersonality = true;
        RemoveIPC = true;

        ReadOnlyPaths = [
          "/sys/fs/cgroup"
          compatibilityCheckpointRoot
          hostControlSocketDirectory
        ];
        ReadWritePaths = [ stateRoot ];
      };
    };
  };
}
