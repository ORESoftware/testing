package dev.oreslang.runtime;

/**
 * Runtime-owned immutable Oreslang values that may cross actor boundaries.
 *
 * Keeping these representations in the runtime avoids an open "Sendable"
 * interface that arbitrary host objects could implement to bypass freezing.
 */
public final class OresValues {
    private OresValues() { }

    public record Complex(double real, double imaginary) {
        public Complex add(Complex other) {
            return new Complex(real + other.real, imaginary + other.imaginary);
        }

        public Complex sub(Complex other) {
            return new Complex(real - other.real, imaginary - other.imaginary);
        }

        public Complex mul(Complex other) {
            return new Complex(
                    real * other.real - imaginary * other.imaginary,
                    real * other.imaginary + imaginary * other.real);
        }

        public Complex div(Complex other) {
            double denominator = other.real * other.real + other.imaginary * other.imaginary;
            return new Complex(
                    (real * other.real + imaginary * other.imaginary) / denominator,
                    (imaginary * other.real - real * other.imaginary) / denominator);
        }

        public Complex negate() {
            return new Complex(-real, -imaginary);
        }

        @Override
        public String toString() {
            return real + (imaginary < 0 ? "" : "+") + imaginary + "i";
        }
    }

    public record OptionValue(boolean present, Object value) {
        public OptionValue {
            if (!present && value != null) {
                throw new IllegalArgumentException("None cannot carry a hidden value");
            }
        }

        @Override
        public String toString() {
            return present ? "Some(" + value + ")" : "None";
        }
    }
}
