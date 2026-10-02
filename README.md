# Oreslang

Oreslang is a statically typed GraalVM/Truffle language with nominal typing by default, explicit structural-call opt-ins, actor-oriented concurrency, hot-loadable code generations, and deny-by-default isolate capabilities.

This repository contains the Java/Truffle reference implementation.

The language is intentionally opinionated:

- static nominal typing by default, with explicit structural compatibility at selected call boundaries;
- private functions by default (`fnc`), with `pub` for exported functions;
- class methods omit `fnc` and have an implicit `self` receiver;
- one return value only (tuples/arrays/records are ordinary single values);
- `val`, `const`, and `let` are the only variable declarations;
- actor heaps are isolated: mutable values are never shared between actors;
- `singleton module` provides one process-coordinated actor/service per canonical code-unit/module identity, capability-gated request/reply access, optional exported class-instance proxies, cycle-checked mailboxes, policy backpressure/timeouts, and generation-guarded hot reload;
- immutable/sendable values may be message-passed, and explicitly frozen regions may be shared read-only;
- isolates are stricter security boundaries for FaaS/mobile workloads, with host access denied and Oreslang APIs capability-gated by default;
- JIT, AOT/interpreter, and AOT-host + guest-JIT hybrid execution profiles;
- file-granular incremental compilation with stable code-unit/package identities and reverse-dependency invalidation;
- flat optional file namespaces and flat modules (neither may nest);
- class-level `static fnc` functions separated from receiver methods;
- first-class function aliases/types and block-only `|args| -> { ... }` lambdas;
- lexical closures with persistent captured environments;
- affine ownership, move checking, `&T` / `&mut T` borrows, immutable-by-default parameters, and `Type mut name` owned-mutation syntax;
- hot reload creates a fresh versioned guest context/generation without requiring FFI or dynamic native libraries;
- direct method calls reuse shared class method definitions; extracted method values bind their receiver safely without rebinding `self`;
- class/module declarations use the mandatory `as` body marker; `as` and `is` are reserved keywords;
- `init routine() => void` is actor-local at file/ordinary-module scope and process-local inside a singleton module;
- multiple named modules may appear in one source file;
- explicit `return` statements;
- generics, tuples, arrays, complex numbers, futures/`await`, lambdas, `defer`, and `try/catch/finally` are language-level features.

The first implementation is developed on a feature branch and will land with an executable Truffle skeleton, grammar/specification, examples, tests, and CI.
