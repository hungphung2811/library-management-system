package lms.sales.shared.domain;

/** Làm tròn số nguyên HALF_UP cho số không âm (ADR-0010 mục 2.4). Không dùng double. */
public final class Rounding {

    private Rounding() {
    }

    /** round(numerator / denominator) HALF_UP; numerator >= 0, denominator > 0. */
    public static long divHalfUp(long numerator, long denominator) {
        if (numerator < 0 || denominator <= 0) {
            throw new IllegalArgumentException("numerator >= 0 và denominator > 0");
        }
        long q = numerator / denominator;
        long r = numerator % denominator;
        return (r >= denominator - r) ? q + 1 : q;
    }

    /** round(base * numerator / denominator), nhân kiểm tràn. */
    public static long mulDivHalfUp(long base, long numerator, long denominator) {
        try {
            return divHalfUp(Math.multiplyExact(base, numerator), denominator);
        } catch (ArithmeticException e) {
            throw SalesException.invalidPrice("Số tiền vượt giới hạn");
        }
    }
}
