
# Portable JSON Schema profile v1

The profile is a fail-closed subset of JSON Schema Draft 2020-12 that all four language targets must enforce equivalently.

Supported initially:

- objects, arrays, strings, integers, numbers, booleans, and null;
- required/optional fields and explicit defaults;
- string/numeric/array bounds and reviewed patterns;
- enums and `const`;
- local `$defs` and local `$ref`;
- explicit `additionalProperties` behavior;
- discriminated `oneOf` unions with one required literal discriminator.

Rejected until equivalent native validation exists everywhere:

- remote runtime references;
- dynamic references;
- unconstrained recursion;
- ambiguous or non-discriminated unions;
- dependent schemas;
- conflicting `allOf` composition;
- any keyword silently ignored by one target.

Generated types alone are not validation. Every target must execute the canonical invalid fixtures and reject them with a stable machine code and JSON Pointer-style path.
