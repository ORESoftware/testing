# Build-time tree shaking

Oreslang executable builds use a closed-world reachability pass before backend lowering.

The optimizer:

1. parses and type-checks the complete program;
2. resolves explicit build-time `const` overrides;
3. propagates constants and folds ternaries / `if` branches;
4. starts from executable entry points (normally `main`);
5. follows symbolic function, field, class, interface, and type references;
6. removes unreachable declarations, empty modules, and imports that are no longer referenced.

This ordering is intentional. Tree shaking is an optimization and must not make invalid source valid merely because a build flag makes a branch unreachable.

## Build switches

Build switches override existing Oreslang `const` declarations. They do not create arbitrary globals and guest code never reads the compiler process environment implicitly.

Example source:

```ores
pub const bool use_a = false;

define module A
  pub fnc foo(): String { return "hi"; }
end

define module B
  pub fnc foo(): String { return "bye"; }
end

type F = typeof fnc() => String;

pub fnc choose(bool t): F {
  return t ? A.foo : B.foo;
}

pub routine main(): void {
  val F selected = choose(use_a);
  stdio.stdout.write(selected());
  return;
}
```

A build frontend may construct the build options from either environment variables or repeated CLI switches:

```text
ORESLANG_BUILD_DEFINES=use_a=true,backend=native
ORESLANG_DEFINE_DEBUG=false

--define=use_a=true
--define=backend=native
```

CLI definitions take precedence over environment definitions. A definition must target a real `const` declaration; overriding `val` or `let` is rejected.

The compiler backend exposes the same path today for build diagnostics:

```bash
ORESLANG_BUILD_DEFINES=use_a=true \
  oreslang-compiler --build-analysis app.ores

oreslang-compiler --build-analysis --define=use_a=true app.ores
```

It prints retained and removed symbols. This is intentionally an analysis surface until the per-application artifact emitter is wired; it already consumes the same pruned AST that the emitter should consume.

With `use_a=true`, the build constant becomes a literal at the call site. The optimizer specializes the simple single-return `choose(true)` call, folds its ternary to `A.foo`, and then performs reachability. `choose`, `B.foo`, and module `B` disappear when nothing else retains them.

The compiler API is:

```java
var defines = BuildOptions.mergeDefines(System.getenv(), cliDefines);
var result = OresCompiler.compileForBuild(
    source,
    BuildOptions.executable(defines));

result.program();          // pruned AST for backend lowering
result.retainedSymbols();  // diagnostics / build reporting
result.removedSymbols();
```

Library builds use `BuildOptions.library(...)` and preserve the public API while still removing private unreachable declarations.

## JVM / GraalVM guidance

The design intentionally follows the same broad model used by GraalVM Native Image: build a closed-world reachability graph and include only program elements required at runtime.

Relevant upstream guidance:

- GraalVM Native Image overview and closed-world reachability:
  https://www.graalvm.org/latest/reference-manual/native-image/
- GraalVM reachability metadata:
  https://www.graalvm.org/latest/reference-manual/native-image/metadata/
- GraalVM file-size optimization (`-Os`) and build reports:
  https://www.graalvm.org/latest/reference-manual/native-image/guides/optimize-for-file-size/
- Oracle `jlink`, for JVM distributions that want a custom runtime containing only required Java modules:
  https://docs.oracle.com/en/java/javase/26/docs/specs/man/jlink.html

### Oreslang native images

Normal native builds continue to use `-O2`. To prefer executable size, combine the native profile with `native-size`:

```bash
mvn -Pnative-aot,native-size -DskipTests package
mvn -Pnative-hybrid,native-size -DskipTests package
mvn -Pnative-isolate,native-size -DskipTests package
```

The `native-size` profile selects GraalVM `-Os`. This is opt-in because GraalVM documents a size/performance tradeoff.

For future per-application native images, the backend should feed the already-pruned Oreslang program to Native Image rather than asking GraalVM to recover language-level reachability from the generic interpreter/runtime. Dynamic Java/JNI/reflection registrations should remain conditional and as narrow as possible so they do not accidentally retain unrelated code.

## Deliberate boundaries

The first pass is symbol-level within an Oreslang program and folds explicit build constants and local immutable constants.

Further optimizer work can build on the same symbolic reachability graph:

- broaden constant-argument specialization beyond simple single-return `fnc` bodies to multi-statement/control-flow partial evaluation;
- cross-file symbol-level linking rather than file-granular import retention;
- class-member-level removal after virtual dispatch / reflection rules are fully specified;
- build reports with retained-byte attribution once the code-emission backend exposes sizes.

Those additions should not change the build-switch contract introduced here.
