{ config, lib, ... }:

let
  cfg = config.services.bmsclLifecycleAgent;
  controlGroup = "beamscale-lifecycle-control";
  stateRoot = "/var/lib/beamscale/lifecycle";
  compatibilityCheckpointRoot = "/var/lib/beamscale/lifecycle-disabled-checkpoints";
  managedCgroupRoot = "/sys/fs/cgroup/beamscale-workloads.slice";
  socketRoot = "/run/beamscale-lifecycle";
  productSocket = "${socketRoot}/product/control.sock";
  productSocketDirectory = builtins.dirOf productSocket;
  hostControlSocket = "${socketRoot}/host/control.sock";
  hostControlSocketDirectory = builtins.dirOf hostControlSocket;
  productUnit = "${cfg.productService}.service";
in
{
  options.services.bmsclLifecycleAgent = {
    enable = lib.mkEnableOption "BeamScale external host lifecycle observer/preflight agent";

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
      description = "Stable BeamScale cluster/cell identity recorded by the shared lifecycle runtime.";
    };

    node = lib.mkOption {
      type = lib.types.str;
      default = config.networking.hostName;
      description = "Stable host/controller identity recorded by the shared lifecycle runtime.";
    };

    productService = lib.mkOption {
      type = lib.types.str;
      description = "Existing NixOS systemd service name, without .service, that runs bmscl-supervisor. The lifecycle module injects the fixed product socket into this exact service and orders itself after it.";
    };

    controlSocketOwner = lib.mkOption {
      type = lib.types.str;
      description = "Exact OS user that runs bmscl-supervisor and owns the private cooperative lifecycle socket directory. This must be supplied by the host deployment; do not guess a tenant or runtime user.";
    };

    reconcileSeconds = lib.mkOption {
      type = lib.types.ints.positive;
      default = 15;
      description = "Observe-only reconciliation cadence while the trusted mutation runtime remains disabled.";
    };

    environment = lib.mkOption {
      type = lib.types.attrsOf lib.types.str;
      default = {};
      description = "Non-secret lifecycle-agent environment only. Distributed lease credentials are intentionally not accepted by this observe-only module.";
    };
  };

  config = lib.mkIf cfg.enable {
    assertions = [
      {
        assertion =
          builtins.match "^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$" cfg.cluster != null;
        message = "BeamScale lifecycle cluster must be a safe shared-agent identity segment";
      }
      {
        assertion =
          builtins.match "^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$" cfg.node != null;
        message = "BeamScale lifecycle node must be a safe shared-agent identity segment";
      }
      {
        assertion =
          builtins.match "^[A-Za-z_][A-Za-z0-9_-]{0,63}$" cfg.controlSocketOwner != null;
        message = "BeamScale lifecycle controlSocketOwner must be an explicit safe OS user name";
      }
      {
        assertion =
          builtins.match "^[A-Za-z0-9][A-Za-z0-9_.@-]{0,95}$" cfg.productService != null
          && cfg.productService != "bmscl-lifecycle-agent";
        message = "BeamScale lifecycle productService must name the existing non-lifecycle bmscl-supervisor systemd service without .service";
      }
    ];

    users.groups.${controlGroup} = {};

    systemd.slices."beamscale-workloads" = {
      description = "BeamScale suspendable workload processes";
      sliceConfig = {
        CPUAccounting = true;
        MemoryAccounting = true;
        TasksAccounting = true;
      };
    };

    systemd.tmpfiles.rules = [
      "d ${stateRoot} 0700 root root -"
      # Current shared-agent preflight requires a checkpoint directory even
      # with hibernation disabled. Keep it read-only and outside any future real
      # checkpoint authority rather than granting CRIU/restore rights today.
      "d ${compatibilityCheckpointRoot} 0500 root root -"
      # Product and trusted host-control sockets must never share a writable
      # parent. Directory write permission is sufficient to unlink/squat a Unix
      # socket entry even when the socket inode itself is root-owned.
      "d ${socketRoot} 0755 root root -"
      "d ${productSocketDirectory} 2770 ${cfg.controlSocketOwner} ${controlGroup} -"
      "d ${hostControlSocketDirectory} 0700 root root -"
    ];

    # Enabling lifecycle observation must also enable the cooperative product
    # bridge on the exact host service. This is deliberately product-local and
    # non-secret: distributed lifecycle authority remains outside the supervisor.
    systemd.services.${cfg.productService}.environment = {
      BMSCL_LIFECYCLE_SOCKET = productSocket;
    };
    systemd.services.${cfg.productService}.serviceConfig.ReadWritePaths =
      lib.mkAfter [ productSocketDirectory ];
    systemd.services.${cfg.productService}.serviceConfig.InaccessiblePaths =
      lib.mkAfter [ hostControlSocketDirectory ];

    systemd.services.bmscl-lifecycle-agent = {
      description = "BeamScale external host lifecycle observer/preflight agent";
      wantedBy = [ "multi-user.target" ];
      requires = [
        "beamscale-workloads.slice"
        productUnit
      ];
      after = [
        "beamscale-workloads.slice"
        productUnit
      ];

      # Match the executable contract that actually ships today. Shared-agent
      # issue #38 tracks the trusted lease, demand/dispatch, workload identity,
      # and transitional recovery adapters required before effects may be enabled.
      environment = cfg.environment // {
        ORES_PROCESS_LIFECYCLE_PRODUCT = "beamscale";
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

        # Observe/preflight-only means no cgroup write, lease network, DAC-bypass,
        # checkpoint, ptrace, mount, or namespace authority is needed yet.
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
