package lms.circulation.reporting.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Top đầu sách được mượn; tên đầu sách do lms-api bổ sung từ catalog. */
public record TopBorrowedTitlesResponse(LocalDate from, LocalDate to, int limit, List<Item> items) {

    public record Item(UUID titleId, long count) {
    }
}
