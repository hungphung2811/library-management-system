package lms.circulation.reporting.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/** Độ chia của biểu đồ theo thời gian: ngày, hoặc tuần ISO (bắt đầu thứ Hai, nhãn là ngày thứ Hai). */
public enum Granularity {
    DAY, WEEK;

    /** Null hoặc rỗng là {@link #DAY}; khác {@code day|week} (không phân biệt hoa thường) thì INVALID_GRANULARITY. */
    public static Granularity parse(String value) {
        if (value == null || value.isBlank()) return DAY;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "day" -> DAY;
            case "week" -> WEEK;
            default -> throw new InvalidReportQueryException("INVALID_GRANULARITY",
                    "granularity chỉ nhận day hoặc week: " + value);
        };
    }

    /** Nhãn bucket chứa ngày {@code date}. */
    public LocalDate bucketOf(LocalDate date) {
        return this == WEEK ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : date;
    }

    public LocalDate next(LocalDate bucket) {
        return this == WEEK ? bucket.plusWeeks(1) : bucket.plusDays(1);
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
