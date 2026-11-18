package lms.circulation.borrowing.infrastructure.integration;

import lms.circulation.borrowing.application.RefundRequestPort;
import lms.circulation.fine.application.FineRefundResponse;
import lms.circulation.fine.application.FineRefundUseCases;
import lms.circulation.fine.application.RequestRefundCommand;
import java.time.LocalDate;
import java.util.UUID;

/** Nối borrowing với tầng application của fine để lập đề nghị hoàn. */
public class RefundRequestAdapter implements RefundRequestPort {

    private final FineRefundUseCases refunds;

    public RefundRequestAdapter(FineRefundUseCases refunds) {
        this.refunds = refunds;
    }

    @Override
    public FineRefundResponse request(UUID borrowingId, UUID bookCopyId, LocalDate lostOn, String restoreStatus,
                                      String checkNote, String method, String referenceCode, UUID requestedBy) {
        return refunds.request(new RequestRefundCommand(borrowingId, bookCopyId, lostOn, restoreStatus, checkNote,
                method, referenceCode, requestedBy));
    }
}
