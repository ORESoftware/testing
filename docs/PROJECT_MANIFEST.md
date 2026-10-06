# Oreslang project manifest

Oreslang projects may define compiler/project configuration in the nearest
`.oreslangc.cfg.toml`. The compiler walks upward from the entry source file
(or from the current working directory when no source file is supplied) and
uses the first manifest it finds.

This is intentionally a project/compiler manifest: lighter than Maven's build
lifecycle, but more project-aware than a source-only compiler switch file.

## Supported v1 contract

```toml
schema_version = "1"

[project]
name = "my-ores-app"
version = "0.1.0"
root = "."

[source]
roots = ["src"]
import_paths = ["lib", "vendor"]

[entrypoints]
main = "src/main.ores"
```

All manifest filesystem paths are resolved relative to `[project].root`,
which itself is relative to the manifest directory unless absolute.

The current implementation intentionally rejects unsupported schema versions
and only assigns behavior to fields that the compiler/runtime actually
consumes. Additional compiler, target, profile, dependency, workspace, and
actor-policy sections should be added version-by-version rather than accepted
and silently ignored.

## Import resolution

Oreslang source identity remains filesystem/path based. The manifest does not
create a Java-style package namespace and source files do not need a top-level
`module` declaration.

Resolution order is:

1. `./foo` and `../foo`: only relative to the importing file.
2. Absolute source path: exactly that path.
3. Bare path such as `pkg/http`: each `source.roots` entry in order.
4. Each `source.import_paths` entry in order.
5. Each `ORESLANG_PATH` entry in order.

For a path without an extension, the resolver tries the exact file and then
`.ores` and `.java`.

Project-local roots intentionally precede ambient `ORESLANG_PATH` entries so
a developer machine cannot silently shadow a dependency pinned by the project.

## ORESLANG_PATH

`ORESLANG_PATH` is the ambient Oreslang import/search path. It is a list of
directories separated by the host OS path-list separator, exactly like
`PATH`:

```bash
# macOS / Linux
export ORESLANG_PATH="$HOME/.oreslang/lib:/opt/oreslang/lib"

# Windows PowerShell
$env:ORESLANG_PATH = 'C:\\oreslang\\lib;D:\\shared\\ores'
```

Relative entries are resolved from the compiler process working directory.
Empty entries are ignored and never mean the current directory; this avoids
accidental dependency injection from a leading/trailing separator or `::`.

The compiler records the resolved target for every loaded import and reuses
that exact mapping during static linking and runtime linking. Compilation and
execution therefore cannot disagree about which file a bare import names.
