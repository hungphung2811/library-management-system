package lms.circulation.reporting.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Tổng quan mượn trả và phạt của tenant tại ngày {@code asOf} (giờ VN). */
public record LendingSummaryResponse(LocalDate asOf, String timezone, Lending lending, Fines fines) {

    public record Lending(long activeBorrowings, long overdueBorrowings, long overdueCopies,
                          long borrowedToday, long returnedToday) {
    }

    public record Fines(long unpaidCount, BigDecimal unpaidAmount) {
    }
}
