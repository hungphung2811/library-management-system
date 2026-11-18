package lms.circulation.fine.application;

import lms.circulation.fine.domain.FineAdjustmentRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Đề nghị miễn/giảm. fineAmount/fineReason/readerId là bản chụp lúc đề nghị. payableAmount là số phải thu sau khi
 * duyệt (0 với WAIVE). status: PENDING, APPROVED, REJECTED.
 */
public record FineAdjustmentResponse(UUID id, UUID fineId, UUID readerId, String fineReason, BigDecimal fineAmount,
                                     String type, BigDecimal payableAmount, String reason, String status,
                                     UUID requestedBy, Instant requestedAt, UUID decidedBy, Instant decidedAt,
                                     String decisionNote, UUID tenantId) {

    public static FineAdjustmentResponse from(FineAdjustmentRequest r) {
        return new FineAdjustmentResponse(r.id(), r.fineId(), r.readerId(), r.fineReason().name(), r.fineAmount(),
                r.kind().name(), r.payableAmount(), r.reason(), r.status().name(), r.requestedBy(), r.requestedAt(),
                r.decidedBy(), r.decidedAt(), r.decisionNote(), r.tenantId());
    }
}
