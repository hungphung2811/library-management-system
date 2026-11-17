package lms.circulation.reporting.application;

import java.time.LocalDate;
import java.util.List;

/** Biểu đồ mượn/trả theo thời gian; {@code granularity} là {@code day} hoặc {@code week}. */
public record BorrowingTrendResponse(LocalDate from, LocalDate to, String granularity, List<Point> points) {

    public record Point(LocalDate bucket, long borrowed, long returned) {
    }
}
