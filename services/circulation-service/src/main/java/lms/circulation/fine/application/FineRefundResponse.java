package lms.circulation.fine.application;

import lms.circulation.fine.domain.FineRefund;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Đề nghị hoàn khoản khi tìm lại sách đã báo mất. mode: REFUND (khoản đã thu: ghi bút toán hoàn refundAmount), WAIVE
 * (khoản chưa thu: duyệt thì miễn toàn phần), RESTORE_ONLY (khoản đã miễn: chỉ đưa bản sao về kho). status: PENDING,
 * APPROVED, REJECTED. Chỉ ghi nhận bút toán, không chuyển tiền thật.
 */
public record FineRefundResponse(UUID id, UUID fineId, UUID borrowingId, UUID bookCopyId, UUID readerId,
                                 LocalDate lostOn, LocalDate foundOn, String restoreStatus, String checkNote,
                                 String refundMethod, String referenceCode, String mode, String fineStatusAtRequest,
                                 BigDecimal collectedAmount, BigDecimal processingFee, BigDecimal refundAmount,
                                 String status, UUID requestedBy, Instant requestedAt, UUID decidedBy,
                                 Instant decidedAt, String decisionNote, UUID tenantId) {

    public static FineRefundResponse from(FineRefund r) {
        return new FineRefundResponse(r.id(), r.fineId(), r.borrowingId(), r.bookCopyId(), r.readerId(), r.lostOn(),
                r.foundOn(), r.restoreStatus().name(), r.checkNote(), r.method().name(), r.referenceCode(),
                r.mode().name(), r.fineStatusAtRequest().name(), r.collectedAmount(), r.processingFee(),
                r.refundAmount(), r.status().name(), r.requestedBy(), r.requestedAt(), r.decidedBy(), r.decidedAt(),
                r.decisionNote(), r.tenantId());
    }
}
