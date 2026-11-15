package lms.circulation.borrowing.application;

import lms.circulation.fine.application.AssessFineCommand;
import lms.circulation.fine.application.FineResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Cổng sang context fine (tầng application của fine): nợ phạt cho QĐ2 và lập phạt khi trả/báo mất. */
public interface FineLedgerPort {

    BigDecimal unpaidTotal(UUID readerId);

    List<FineResponse> assess(AssessFineCommand command);
}
