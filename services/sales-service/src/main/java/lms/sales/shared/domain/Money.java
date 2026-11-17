package lms.sales.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Tiền VND, số nguyên đồng (không phần lẻ), không âm (ADR-0010 mục 2.4). */
public record Money(long amount) implements Comparable<Money> {

    public static final String CURRENCY = "VND";
    public static final Money ZERO = new Money(0);

    public Money {
        if (amount < 0) {
            throw SalesException.invalidPrice("Số tiền không được âm");
        }
    }

    public static Money of(long amount) {
        return new Money(amount);
    }

    /** Đổi giá bìa BigDecimal của catalog sang đồng: setScale(0, HALF_UP). */
    public static Money fromCoverPrice(BigDecimal coverPrice) {
        if (coverPrice == null) {
            throw SalesException.invalidPrice("Đầu sách chưa có giá");
        }
        try {
            return new Money(coverPrice.setScale(0, RoundingMode.HALF_UP).longValueExact());
        } catch (ArithmeticException e) {
            throw SalesException.invalidPrice("Giá vượt giới hạn");
        }
    }

    public Money plus(Money other) {
        try {
            return new Money(Math.addExact(amount, other.amount));
        } catch (ArithmeticException e) {
            throw SalesException.invalidPrice("Số tiền vượt giới hạn");
        }
    }

    /** Ném INVALID_PRICE nếu kết quả âm. */
    public Money minus(Money other) {
        return new Money(amount - other.amount);
    }

    public Money times(int quantity) {
        if (quantity < 0) {
            throw SalesException.invalidQuantity("Số lượng không được âm");
        }
        try {
            return new Money(Math.multiplyExact(amount, (long) quantity));
        } catch (ArithmeticException e) {
            throw SalesException.invalidPrice("Số tiền vượt giới hạn");
        }
    }

    public boolean isZero() {
        return amount == 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(amount, other.amount);
    }
}
