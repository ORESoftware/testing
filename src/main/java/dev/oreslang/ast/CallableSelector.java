package dev.oreslang.ast;

import java.util.Objects;

/**
 * Canonical closed-world dispatch selector for class callables.
 *
 * <p>Oreslang overloads class callables by arity only. Parameter runtime types
 * never participate in overload selection. The owner class supplies the vtable
 * / static-table namespace; this selector supplies the slot within that table.
 *
 * <p>This representation is intentionally AOT-friendly: every call site knows
 * its arity from syntax, so an AOT compiler can precompute the slot while a JIT
 * may cache/specialize the same slot without changing language semantics.
 */
public record CallableSelector(Kind kind, String name, int arity) {
    public enum Kind { INSTANCE, STATIC }

    public CallableSelector {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("callable selector name cannot be blank");
        if (arity < 0) throw new IllegalArgumentException("callable selector arity cannot be negative");
    }

    public static CallableSelector of(Ast.MethodDecl method) {
        Objects.requireNonNull(method, "method");
        return new CallableSelector(
                method.isStatic() ? Kind.STATIC : Kind.INSTANCE,
                method.name(),
                method.arity());
    }

    public static CallableSelector instance(String name, int arity) {
        return new CallableSelector(Kind.INSTANCE, name, arity);
    }

    public static CallableSelector staticFunction(String name, int arity) {
        return new CallableSelector(Kind.STATIC, name, arity);
    }

    public boolean matches(Ast.MethodDecl method) {
        return equals(of(method));
    }

    /** Stable link/vtable symbol fragment; owner qualification is added by the linker. */
    public String mangledName() {
        return (kind == Kind.STATIC ? "static$" : "instance$")
                + name + "$arity" + arity;
    }

    /** Type-contract key keeps generic arity separate from overload selection. */
    public String contractKey(int genericArity) {
        if (genericArity < 0) throw new IllegalArgumentException("generic arity cannot be negative");
        return mangledName() + "$generics" + genericArity;
    }
}
