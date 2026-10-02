package dev.oreslang.types;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Types {
    private Types() { }

    public sealed interface Type permits Primitive, Named, Borrow, ClassNamespace, Record, Function, ListType, Tuple, Generic, StringLiteral, Unknown { }

    public enum Primitive implements Type {
        INT, FLOAT, DECIMAL, COMPLEX, BOOL, STRING, VOID, NULL
    }

    public record Named(String name, List<Type> arguments) implements Type {
        public Named { arguments = List.copyOf(arguments); }
    }

    /** Rust-style compile-time borrow; erased by the interpreter runtime. */
    public record Borrow(Type target, boolean mutable) implements Type { }

    /** Compile-time meta-value for access to static class functions. */
    public record ClassNamespace(String className) implements Type { }

    public record Record(Map<String, Type> members) implements Type {
        public Record { members = Collections.unmodifiableMap(new LinkedHashMap<>(members)); }
    }

    public record Function(List<Type> parameters, Type result) implements Type {
        public Function { parameters = List.copyOf(parameters); }
    }

    public record ListType(Type element) implements Type { }

    public record Tuple(List<Type> elements) implements Type {
        public Tuple { elements = List.copyOf(elements); }
    }

    public record Generic(String name) implements Type { }

    public record StringLiteral(String value) implements Type { }

    public enum Unknown implements Type { INSTANCE }

    public static boolean isAssignable(Type from, Type to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        from = canonicalBuiltin(from);
        to = canonicalBuiltin(to);
        if (from == Unknown.INSTANCE || to == Unknown.INSTANCE) return true;
        if (to instanceof Generic || from instanceof Generic) return true;
        if (from.equals(to)) return true;
        if (from instanceof StringLiteral && to == Primitive.STRING) return true;

        if (from instanceof Borrow source && to instanceof Borrow target) {
            if (target.mutable() && !source.mutable()) return false;
            return isAssignable(source.target(), target.target()) && isAssignable(target.target(), source.target());
        }

        if (from instanceof Named source && to instanceof Named target && source.name().equals(target.name())) {
            if (source.arguments().size() != target.arguments().size()) return false;
            for (int i = 0; i < source.arguments().size(); i++) {
                Type a = source.arguments().get(i), b = target.arguments().get(i);
                if (!isAssignable(a, b) || !isAssignable(b, a)) return false;
            }
            return true;
        }

        if (from instanceof Record source && to instanceof Record target) {
            for (Map.Entry<String, Type> required : target.members().entrySet()) {
                Type actual = source.members().get(required.getKey());
                if (actual == null || !isAssignable(actual, required.getValue())) return false;
            }
            return true;
        }

        if (from instanceof ListType source && to instanceof ListType target) {
            return isAssignable(source.element(), target.element()) && isAssignable(target.element(), source.element());
        }

        if (from instanceof Tuple source && to instanceof Tuple target) {
            if (source.elements().size() != target.elements().size()) return false;
            for (int i = 0; i < source.elements().size(); i++) {
                if (!isAssignable(source.elements().get(i), target.elements().get(i))) return false;
            }
            return true;
        }

        if (from instanceof Function source && to instanceof Function target) {
            if (source.parameters().size() != target.parameters().size()) return false;
            for (int i = 0; i < source.parameters().size(); i++) {
                if (!isAssignable(target.parameters().get(i), source.parameters().get(i))) return false;
            }
            return isAssignable(source.result(), target.result());
        }

        return numericWidening(from, to);
    }

    private static Type canonicalBuiltin(Type type) {
        if (!(type instanceof Named named) || !named.arguments().isEmpty()) return type;
        return switch (named.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "int", "uint", "bigint" -> Primitive.INT;
            case "f32", "f64", "float" -> Primitive.FLOAT;
            case "decimal" -> Primitive.DECIMAL;
            case "complex64", "complex128", "complex" -> Primitive.COMPLEX;
            case "bool", "Bool" -> Primitive.BOOL;
            case "str", "string", "String" -> Primitive.STRING;
            case "void" -> Primitive.VOID;
            default -> type;
        };
    }

    private static boolean numericWidening(Type from, Type to) {
        if (from == Primitive.INT && (to == Primitive.FLOAT || to == Primitive.DECIMAL || to == Primitive.COMPLEX)) return true;
        if ((from == Primitive.FLOAT || from == Primitive.DECIMAL) && to == Primitive.COMPLEX) return true;
        return false;
    }

    public static Type numericJoin(Type left, Type right) {
        if (!isNumeric(left) || !isNumeric(right)) return Unknown.INSTANCE;
        if (left == Primitive.COMPLEX || right == Primitive.COMPLEX) return Primitive.COMPLEX;
        if (left == Primitive.DECIMAL || right == Primitive.DECIMAL) return Primitive.DECIMAL;
        if (left == Primitive.FLOAT || right == Primitive.FLOAT) return Primitive.FLOAT;
        return Primitive.INT;
    }

    public static boolean isNumeric(Type type) {
        return type == Primitive.INT || type == Primitive.FLOAT || type == Primitive.DECIMAL || type == Primitive.COMPLEX;
    }
}
