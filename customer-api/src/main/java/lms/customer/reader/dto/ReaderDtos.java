package lms.customer.reader.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTO công khai của cổng độc giả (ADR-0023 2.4). Là record allowlist: KHÔNG có userId, readerId, tenantId, copyId,
 * bookCopyId; trường nội bộ nào circulation/identity thêm sau này cũng không tự lộ ra (client ánh xạ từng trường).
 */
public final class ReaderDtos {

    private ReaderDtos() {
    }

    /** condition: GOOD, DAMAGED, LOST; null khi còn đang mượn. */
    public record BorrowingItemView(LocalDate returnDate, String condition) {
    }

    public record BorrowingView(UUID id, String status, LocalDate borrowDate, LocalDate dueDate, int renewCount,
                                List<BorrowingItemView> items) {
    }

    public record FineView(UUID id, UUID borrowingId, String reason, BigDecimal amount, String status,
                           Instant createdAt, Instant paidAt) {
    }

    public record FinesView(BigDecimal totalUnpaid, List<FineView> fines) {
    }

    /** bookRef = titleId (như ADR-0011 2.8); title null nếu catalog không tra được. */
    public record ReservationView(UUID id, UUID bookRef, String title, String status, long queuePosition,
                                  Instant reservedAt, LocalDate holdUntil) {
    }

    public record ReaderProfileView(String cardNo, String fullName, String email, String readerType,
                                    LocalDate cardExpiryDate, String status, boolean cardValid, String phone,
                                    LocalDate dateOfBirth, String gender, String address, String faculty,
                                    String className, String memberCode, LocalDate cardIssuedDate) {
    }
}
