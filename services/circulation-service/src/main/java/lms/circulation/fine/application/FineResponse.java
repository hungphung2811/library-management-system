package lms.circulation.fine.application;

import lms.circulation.fine.domain.Fine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FineResponse(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, String reason,
                           BigDecimal amount, String status, Instant createdAt, Instant paidAt) {

    public static FineResponse from(Fine f) {
        return new FineResponse(f.id(), f.readerId(), f.borrowingId(), f.bookCopyId(), f.reason().name(),
                f.amount(), f.status().name(), f.createdAt(), f.paidAt());
    }
}
