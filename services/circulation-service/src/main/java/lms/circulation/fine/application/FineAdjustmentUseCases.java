package lms.circulation.fine.application;

import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineAdjustmentRepository;
import lms.circulation.fine.domain.FineAdjustmentRequest;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineRuleException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Miễn/giảm khoản phạt chưa thu có người duyệt (không có hạn mức tự duyệt: mọi đề nghị cần người khác duyệt). Mỗi use
 * case chỉ ghi circulation_db trong một transaction. Người duyệt phải khác người đề nghị, kiểm trong domain.
 */
public class FineAdjustmentUseCases {

    private final FineRepository fines;
    private final FineAdjustmentRepository adjustments;
    private final PublishFineEventPort eventPort;
    private final Clock clock;

    public FineAdjustmentUseCases(FineRepository fines, FineAdjustmentRepository adjustments,
                                  PublishFineEventPort eventPort, Clock clock) {
        this.fines = fines;
        this.adjustments = adjustments;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /** Thủ thư đề nghị; một khoản có tối đa một đề nghị đang chờ (409 ADJUSTMENT_ALREADY_PENDING). */
    @Transactional
    public FineAdjustmentResponse request(UUID fineId, FineAdjustmentRequests.Create body, UUID requestedBy) {
        Fine fine = fines.findByIdForUpdate(fineId).orElseThrow(() -> FineRuleException.notFound(
                "FINE_NOT_FOUND", "Không tìm thấy khoản phạt: " + fineId));
        FineAdjustmentRequest request = FineAdjustmentRequest.request(fine, body.type(), body.payableAmount(),
                body.reason(), requestedBy, Instant.now(clock));
        if (adjustments.existsPendingForFine(fineId)) {
            throw FineRuleException.conflict("ADJUSTMENT_ALREADY_PENDING", "Khoản phạt đã có đề nghị miễn/giảm đang chờ duyệt");
        }
        return FineAdjustmentResponse.from(adjustments.save(request));
    }

    /** Duyệt: áp miễn/giảm lên khoản phạt và phát FineWaivedEvent (best-effort). */
    @Transactional
    public FineAdjustmentResponse approve(UUID requestId, FineAdjustmentRequests.Decision body, UUID approverId) {
        FineAdjustmentRequest request = load(requestId);
        Instant now = Instant.now(clock);
        request.approve(approverId, body == null ? null : body.note(), now);
        Fine fine = fines.findByIdForUpdate(request.fineId()).orElseThrow(() -> FineRuleException.notFound(
                "FINE_NOT_FOUND", "Không tìm thấy khoản phạt: " + request.fineId()));
        fine.applyAdjustment(request.kind(), request.payableAmount(), request.requestedBy(), approverId, now);
        fines.save(fine);
        FineAdjustmentRequest saved = adjustments.save(request);
        fine.pullEvents().forEach(eventPort::publish);
        return FineAdjustmentResponse.from(saved);
    }

    @Transactional
    public FineAdjustmentResponse reject(UUID requestId, FineAdjustmentRequests.Decision body, UUID approverId) {
        FineAdjustmentRequest request = load(requestId);
        request.reject(approverId, body == null ? null : body.note(), Instant.now(clock));
        return FineAdjustmentResponse.from(adjustments.save(request));
    }

    @Transactional(readOnly = true)
    public FinePageResponse<FineAdjustmentResponse> list(FineAdjustmentRequests.Search query) {
        int page = FinePageResponse.pageOf(query.page());
        int size = FinePageResponse.sizeOf(query.size());
        FineAdjustmentRepository.Page result = adjustments.search(
                new FineAdjustmentRepository.Criteria(query.status(), query.fineId(), query.readerId(), page, size));
        return new FinePageResponse<>(result.items().stream().map(FineAdjustmentResponse::from).toList(),
                result.total(), page, size);
    }

    private FineAdjustmentRequest load(UUID requestId) {
        return adjustments.findByIdForUpdate(requestId).orElseThrow(() -> FineRuleException.notFound(
                "ADJUSTMENT_NOT_FOUND", "Không tìm thấy đề nghị miễn/giảm: " + requestId));
    }
}
