package lms.circulation.reporting.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Khoảng ngày [from, to] (cả hai đầu bao gồm, theo ngày giờ VN), tối đa {@value #MAX_DAYS} ngày. */
public record DateRange(LocalDate from, LocalDate to) {

    public static final int MAX_DAYS = 366;
    /** Mặc định: 30 ngày gần nhất tính tới hôm nay. */
    public static final int DEFAULT_DAYS = 30;

    public DateRange {
        if (from == null || to == null) throw new InvalidReportQueryException("INVALID_RANGE", "Thiếu from hoặc to");
        if (from.isAfter(to)) throw new InvalidReportQueryException("INVALID_RANGE", "from phải không sau to");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new InvalidReportQueryException("RANGE_TOO_LARGE", "Khoảng ngày tối đa " + MAX_DAYS + " ngày");
        }
    }

    /** Điền mặc định: to = hôm nay, from = to - 29 ngày khi thiếu. */
    public static DateRange resolve(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        return new DateRange(start, end);
    }
}
