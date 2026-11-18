package lms.circulation.fine.application;

import lms.circulation.fine.domain.AdjustmentKind;
import lms.circulation.fine.domain.ApprovalStatus;
import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRefund;
import lms.circulation.fine.domain.FineRefundRepository;
import lms.circulation.fine.domain.FineRefundedEvent;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineRuleException;
import lms.circulation.fine.domain.RefundMode;
import lms.circulation.policy.application.CompensationPolicyResponse;
import lms.circulation.policy.application.GetCompensationPolicyUseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hoàn khoản đền bù khi tìm lại sách đã báo mất (chỉ ghi nhận bút toán, không chuyển tiền thật). Đề nghị do thủ thư lập
 * (qua borrowing); duyệt bởi người khác người đề nghị. Duyệt: ghi bút toán REFUND (khoản đã thu) hoặc miễn toàn phần
 * (khoản chưa thu), rồi đưa bản sao từ LOST về AVAILABLE/DAMAGED ở catalog ở bước CUỐI trong cùng transaction: catalog
 * lỗi thì rollback toàn bộ, không có bút toán. Cấu hình đọc qua use case gốc (không cache).
 */
public class FineRefundUseCases {

    private final FineRepository fines;
    private final FineRefundRepository refunds;
    private final GetCompensationPolicyUseCase compensation;
    private final CopyRestorePort copies;
    private final PublishFineEventPort eventPort;
    private final Clock clock;

    public FineRefundUseCases(FineRepository fines, FineRefundRepository refunds,
                              GetCompensationPolicyUseCase compensation, CopyRestorePort copies,
                              PublishFineEventPort eventPort, Clock clock) {
        this.fines = fines;
        this.refunds = refunds;
        this.compensation = compensation;
        this.copies = copies;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public FineRefundResponse request(RequestRefundCommand command) {
        Fine found = fines.findByItemAndReason(command.borrowingId(), command.bookCopyId(), FineReason.LOST)
                .orElseThrow(() -> FineRuleException.notFound("LOST_FINE_NOT_FOUND",
                        "Không có khoản bồi thường mất cho bản sao này trong lượt mượn"));
        Fine fine = fines.findByIdForUpdate(found.id()).orElseThrow();
        if (refunds.existsActiveForFine(fine.id())) {
            throw FineRuleException.conflict("REFUND_ALREADY_EXISTS", "Khoản bồi thường mất này đã có đề nghị hoàn (chờ duyệt hoặc đã duyệt)");
        }
        CompensationPolicyResponse policy = compensation.execute();
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        lms.circulation.fine.domain.RestoreStatus restore = parse(lms.circulation.fine.domain.RestoreStatus.class,
                command.restoreStatus(), "RESTORE_STATUS_REQUIRED", "Cần kiểm tra tình trạng: AVAILABLE hoặc DAMAGED");
        lms.circulation.fine.domain.RefundMethod method = parse(lms.circulation.fine.domain.RefundMethod.class,
                command.refundMethod(), "REFUND_METHOD_REQUIRED", "Cần chọn hình thức hoàn: CASH hoặc BANK_TRANSFER");
        FineRefund refund = FineRefund.request(fine, command.lostOn(), today, policy.foundWindowDays(),
                restore, command.checkNote(), method, command.referenceCode(),
                policy.refundProcessingFee(), command.requestedBy(), now);
        return FineRefundResponse.from(refunds.save(refund));
    }

    @Transactional
    public FineRefundResponse approve(UUID refundId, FineAdjustmentRequests.Decision body, UUID approverId) {
        FineRefund refund = load(refundId);
        Instant now = Instant.now(clock);
        refund.approve(approverId, body == null ? null : body.note(), now);
        Fine fine = fines.findByIdForUpdate(refund.fineId()).orElseThrow(() -> FineRuleException.notFound(
                "FINE_NOT_FOUND", "Không tìm thấy khoản phạt: " + refund.fineId()));
        refund.requireFineStateUnchanged(fine);
        if (refund.mode() == RefundMode.WAIVE) {
            fine.waiveRemainderForRefund(refund.requestedBy(), approverId, now);
            fines.save(fine);
        }
        FineRefund saved = refunds.save(refund);
        // Bước cuối: lỗi catalog ném ngoại lệ, rollback cả khoản miễn và bút toán hoàn
        copies.restore(refund.bookCopyId(), refund.restoreStatus() == lms.circulation.fine.domain.RestoreStatus.AVAILABLE
                ? CopyRestorePort.Target.AVAILABLE : CopyRestorePort.Target.DAMAGED);
        fine.pullEvents().forEach(eventPort::publish);
        if (refund.mode() == RefundMode.REFUND) {
            eventPort.publish(new FineRefundedEvent(UUID.randomUUID(), refund.id(), refund.fineId(), refund.readerId(),
                    refund.bookCopyId(), refund.refundAmount(), refund.method().name(), refund.requestedBy(),
                    approverId, now));
        }
        return FineRefundResponse.from(saved);
    }

    @Transactional
    public FineRefundResponse reject(UUID refundId, FineAdjustmentRequests.Decision body, UUID approverId) {
        FineRefund refund = load(refundId);
        refund.reject(approverId, body == null ? null : body.note(), Instant.now(clock));
        return FineRefundResponse.from(refunds.save(refund));
    }

    @Transactional(readOnly = true)
    public FinePageResponse<FineRefundResponse> list(Search query) {
        int page = FinePageResponse.pageOf(query.page());
        int size = FinePageResponse.sizeOf(query.size());
        FineRefundRepository.Page result = refunds.search(
                new FineRefundRepository.Criteria(query.status(), query.readerId(), page, size));
        return new FinePageResponse<>(result.items().stream().map(FineRefundResponse::from).toList(),
                result.total(), page, size);
    }

    /** Bộ lọc danh sách đề nghị hoàn; mọi trường tùy chọn. size tối đa 100 (mặc định 20), page từ 0. */
    public record Search(ApprovalStatus status, UUID readerId, Integer page, Integer size) {
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String code, String message) {
        if (value == null || value.isBlank()) throw FineRuleException.invalid(code, message);
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw FineRuleException.invalid(code, message);
        }
    }

    private FineRefund load(UUID refundId) {
        return refunds.findByIdForUpdate(refundId).orElseThrow(() -> FineRuleException.notFound(
                "REFUND_NOT_FOUND", "Không tìm thấy đề nghị hoàn: " + refundId));
    }
}
