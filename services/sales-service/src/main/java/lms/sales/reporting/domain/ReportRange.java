package lms.sales.reporting.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Khoảng ngày [from, to] (cả hai bao gồm) theo ngày giờ Việt Nam (ADR-0018 mục 5.1). Mốc Instant tính ở đây để
 * truy vấn lọc trên cột gốc (dùng được chỉ mục). Tối đa {@link #MAX_DAYS} ngày.
 */
public record ReportRange(LocalDate from, LocalDate to) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int MAX_DAYS = 366;
    public static final int DEFAULT_DAYS = 30;

    public ReportRange {
        if (from == null || to == null) {
            throw SalesException.invalidRequest("Thiếu from/to");
        }
        if (from.isAfter(to)) {
            throw new SalesException(ErrorCode.INVALID_RANGE, "from phải trước hoặc bằng to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new SalesException(ErrorCode.RANGE_TOO_LARGE, "Khoảng tối đa " + MAX_DAYS + " ngày");
        }
    }

    /** Thiếu to thì là hôm nay; thiếu from thì 30 ngày gần nhất tính tới to. */
    public static ReportRange of(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        return new ReportRange(start, end);
    }

    public static ReportRange singleDay(LocalDate day) {
        return new ReportRange(day, day);
    }

    /** 00:00 giờ VN của from. */
    public Instant start() {
        return from.atStartOfDay(ZONE).toInstant();
    }

    /** 00:00 giờ VN của ngày sau to (loại trừ). */
    public Instant endExclusive() {
        return to.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    public int days() {
        return (int) (ChronoUnit.DAYS.between(from, to) + 1);
    }

    public List<LocalDate> dates() {
        List<LocalDate> out = new ArrayList<>(days());
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }
}
