package lms.sales.ordering.application;

import lms.sales.shared.domain.DuplicateClientRequestException;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Tạo phiếu trả. KHÔNG transactional: gọi {@link CommitReturnUseCase} (một transaction) và, nếu hai request cùng
 * clientRequestId va nhau ở ràng buộc UNIQUE, trả phiếu đã tạo (không tạo hai lần, không trả hai lần).
 * {@link #create} áp cửa sổ N ngày (409 RETURN_WINDOW_EXPIRED khi quá hạn); {@link #createLate} (khóa riêng) bỏ
 * kiểm N ngày, bắt buộc lý do (400 LATE_REASON_REQUIRED), audit RETURN_LATE_APPROVED.
 */
public class CreateReturnUseCase {

    private final CommitReturnUseCase commit;
    private final SalesReturnRepository returns;

    public CreateReturnUseCase(CommitReturnUseCase commit, SalesReturnRepository returns) {
        this.commit = commit;
        this.returns = returns;
    }

    public ReturnResponse create(CreateReturnCommand cmd) {
        return run(cmd, false);
    }

    public ReturnResponse createLate(CreateReturnCommand cmd) {
        if (cmd.lateReason() == null || cmd.lateReason().isBlank()) {
            throw new SalesException(ErrorCode.LATE_REASON_REQUIRED, "Bắt buộc nhập lý do trả quá hạn");
        }
        return run(cmd, true);
    }

    private ReturnResponse run(CreateReturnCommand cmd, boolean late) {
        if (cmd.clientRequestId() == null) {
            throw SalesException.invalidRequest("Thiếu clientRequestId");
        }
        if (cmd.reason() == null || cmd.reason().isBlank()) {
            throw SalesException.reasonRequired();
        }
        try {
            return commit.execute(cmd, late);
        } catch (DuplicateClientRequestException e) {
            return commit.replay(cmd.tenantId(), cmd.clientRequestId()).orElseThrow(() -> e);
        }
    }
}
