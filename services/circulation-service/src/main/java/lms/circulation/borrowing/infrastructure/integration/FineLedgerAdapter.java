package lms.circulation.borrowing.infrastructure.integration;

import lms.circulation.borrowing.application.FineLedgerPort;
import lms.circulation.fine.application.AssessFineCommand;
import lms.circulation.fine.application.AssessFineUseCase;
import lms.circulation.fine.application.FineResponse;
import lms.circulation.fine.application.GetReaderFinesUseCase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Nối borrowing với tầng application của fine (cùng service, cùng transaction). */
public class FineLedgerAdapter implements FineLedgerPort {

    private final GetReaderFinesUseCase query;
    private final AssessFineUseCase assess;

    public FineLedgerAdapter(GetReaderFinesUseCase query, AssessFineUseCase assess) {
        this.query = query;
        this.assess = assess;
    }

    @Override
    public BigDecimal unpaidTotal(UUID readerId) {
        return query.unpaidTotal(readerId);
    }

    @Override
    public List<FineResponse> assess(AssessFineCommand command) {
        return assess.execute(command);
    }
}
