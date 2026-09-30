{ config, lib, ... }:

let
  cfg = config.services.bmsclRuntimeHostStartupHardening;
in
{
  options.services.bmsclRuntimeHostStartupHardening = {
    enable = lib.mkEnableOption "BeamScale runtime-host fail-closed startup environment preflight";

    package = lib.mkOption {
      type = lib.types.package;
      description = "Pinned package containing bmscl-runtime-host-env-check alongside bmscl-runtime-host.";
    };

    runtimeHostService = lib.mkOption {
      type = lib.types.str;
      description = "Existing systemd service name, without .service, that launches bmscl-runtime-host.";
    };

    validatorBinary = lib.mkOption {
      type = lib.types.str;
      default = "bmscl-runtime-host-env-check";
      description = "Fail-closed startup validator shipped by the runtime-host package.";
    };
  };

  config = lib.mkIf cfg.enable {
    assertions = [
      {
        assertion =
          builtins.match "^[A-Za-z0-9][A-Za-z0-9_.@-]{0,95}$" cfg.runtimeHostService != null;
        message = "BeamScale runtimeHostService must be an explicit safe systemd service name";
      }
      {
        assertion = cfg.runtimeHostService != "bmscl-lifecycle-agent";
        message = "BeamScale runtime host startup hardening must not target the lifecycle agent service";
      }
      {
        assertion =
          builtins.match "^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$" cfg.validatorBinary != null;
        message = "BeamScale runtime-host validatorBinary must be a safe executable basename";
      }
    ];

    # ExecStartPre inherits the exact service environment and EnvironmentFile
    # values that the runtime host will receive. Any malformed explicit backend,
    # numeric limit, path, or bind address therefore fails before the trusted host
    # daemon starts or receives process/network authority. This module augments an
    # existing runtime-host service and never guesses that service's privileges.
    systemd.services.${cfg.runtimeHostService}.serviceConfig.ExecStartPre = lib.mkBefore [
      "${cfg.package}/bin/${cfg.validatorBinary}"
    ];
  };
}
