package dev.oreslang.launcher;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OresMain {
    private OresMain() { }

    public static void main(String[] args) throws Exception {
        boolean strict = false;
        String mode = "jit";
        String platform = "server";
        List<IsolatePolicy.Capability> additionalCapabilities = new ArrayList<>();
        String filename = null;

        for (String arg : args) {
            if (arg.equals("--strict-isolate")) strict = true;
            else if (arg.startsWith("--mode=")) mode = arg.substring("--mode=".length());
            else if (arg.startsWith("--platform=")) platform = arg.substring("--platform=".length());
            else if (arg.startsWith("--allow=")) {
                String raw = arg.substring("--allow=".length());
                if (!raw.isBlank()) {
                    for (String value : raw.split(",")) {
                        additionalCapabilities.add(IsolatePolicy.Capability.valueOf(value.trim().toUpperCase(Locale.ROOT)));
                    }
                }
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("unknown option: " + arg);
            } else if (filename == null) filename = arg;
            else throw new IllegalArgumentException("only one .ores file may be supplied");
        }

        if (filename == null) {
            System.err.println("usage: ores [--strict-isolate] [--mode=aot|jit|hybrid] [--platform=server|windows|macos|linux|android|ios] [--allow=CAP,...] <file.ores>");
            System.exit(2);
            return;
        }

        Path path = Path.of(filename);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("not a file: " + path);

        ExecutionProfile profile = ExecutionProfile.parse(mode, platform);
        IsolatePolicy policy = strict ? IsolatePolicy.strictFaas() : IsolatePolicy.developer();
        if (!additionalCapabilities.isEmpty()) {
            policy = policy.withCapabilities(additionalCapabilities.toArray(IsolatePolicy.Capability[]::new));
        }

        LinkedProgramRunner.run(path, policy, profile, System.out, System.err);
    }
}
