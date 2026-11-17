package lms.api.circulation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FineView(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, String reason,
                       BigDecimal amount, String status, Instant createdAt, Instant paidAt, UUID tenantId) {
}
