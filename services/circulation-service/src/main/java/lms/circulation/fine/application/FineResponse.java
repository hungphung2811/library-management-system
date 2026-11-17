package lms.circulation.fine.application;

import lms.circulation.fine.domain.Fine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** tenantId: tenant sở hữu khoản phạt (ADR-0008); null ở phản hồi của thao tác ghi (khoản vừa lập chưa đọc lại từ DB). */
public record FineResponse(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, String reason,
                           BigDecimal amount, String status, Instant createdAt, Instant paidAt, UUID tenantId) {

    public FineResponse(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, String reason,
                        BigDecimal amount, String status, Instant createdAt, Instant paidAt) {
        this(id, readerId, borrowingId, bookCopyId, reason, amount, status, createdAt, paidAt, null);
    }

    public static FineResponse from(Fine f) {
        return new FineResponse(f.id(), f.readerId(), f.borrowingId(), f.bookCopyId(), f.reason().name(),
                f.amount(), f.status().name(), f.createdAt(), f.paidAt(), f.tenantId());
    }
}
