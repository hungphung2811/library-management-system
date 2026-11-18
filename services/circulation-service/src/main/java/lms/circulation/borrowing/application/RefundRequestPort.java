package lms.circulation.borrowing.application;

import lms.circulation.fine.application.FineRefundResponse;
import java.time.LocalDate;
import java.util.UUID;

/** Cổng sang tầng application của fine: lập đề nghị hoàn khoản (cùng service, cùng transaction). */
public interface RefundRequestPort {

    FineRefundResponse request(UUID borrowingId, UUID bookCopyId, LocalDate lostOn, String restoreStatus,
                               String checkNote, String method, String referenceCode, UUID requestedBy);
}
