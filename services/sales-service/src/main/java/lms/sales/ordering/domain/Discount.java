package lms.sales.ordering.domain;

import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rate;
import lms.sales.shared.domain.SalesException;

/** Giảm giá: số tiền (đồng) hoặc phần trăm (basis point). */
public record Discount(Type type, long value) {

    public enum Type { AMOUNT, PERCENT }

    public Discount {
        if (type == null) {
            throw SalesException.invalidRequest("Thiếu loại giảm giá");
        }
        if (value < 0) {
            throw new SalesException(ErrorCode.DISCOUNT_EXCEEDS_AMOUNT, "Giảm giá không được âm");
        }
        if (type == Type.PERCENT && value > Rate.MAX_BPS) {
            throw new SalesException(ErrorCode.DISCOUNT_EXCEEDS_AMOUNT, "Giảm giá phần trăm tối đa 100%");
        }
    }

    public static Discount amount(long dong) {
        return new Discount(Type.AMOUNT, dong);
    }

    public static Discount percent(int bps) {
        return new Discount(Type.PERCENT, bps);
    }

    /** Số tiền giảm trên cơ sở {@code base}; không vượt base (nếu vượt: DISCOUNT_EXCEEDS_AMOUNT). */
    public Money resolve(Money base) {
        long amount = type == Type.AMOUNT ? value : Rate.ofBps((int) value).percentOf(base.amount());
        if (amount > base.amount()) {
            throw new SalesException(ErrorCode.DISCOUNT_EXCEEDS_AMOUNT, "Giảm giá vượt giá trị");
        }
        return Money.of(amount);
    }
}
