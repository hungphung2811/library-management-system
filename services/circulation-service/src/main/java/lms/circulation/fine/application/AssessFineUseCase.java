package lms.circulation.fine.application;

import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineCalculator;
import lms.circulation.fine.domain.FineLine;
import lms.circulation.fine.domain.FineRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lập phí phạt khi trả/báo mất (QĐ3, QĐ4, QĐ8). Idempotent theo (lượt mượn, bản sao, lý do): khoản đã có thì bỏ qua.
 * Chạy chung transaction với use case của borrowing gọi nó.
 */
public class AssessFineUseCase {

    private final FineCalculator calculator;
    private final FineRepository repository;
    private final PublishFineEventPort eventPort;
    private final Clock clock;

    public AssessFineUseCase(FineCalculator calculator, FineRepository repository, PublishFineEventPort eventPort,
                             Clock clock) {
        this.calculator = calculator;
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public List<FineResponse> execute(AssessFineCommand command) {
        List<FineLine> lines = switch (command.outcome()) {
            case LOST -> calculator.assessLoss(command.coverPrice());
            case RETURNED_GOOD -> calculator.assessReturn(command.overdueDays(), command.coverPrice(), false);
            case RETURNED_DAMAGED -> calculator.assessReturn(command.overdueDays(), command.coverPrice(), true);
        };
        Instant now = Instant.now(clock);
        List<FineResponse> issued = new ArrayList<>();
        for (FineLine line : lines) {
            if (repository.exists(command.borrowingId(), command.bookCopyId(), line.reason())) {
                continue;
            }
            Fine fine = Fine.issue(command.readerId(), command.borrowingId(), command.bookCopyId(), line, now);
            repository.save(fine);
            fine.pullEvents().forEach(eventPort::publish);
            issued.add(FineResponse.from(fine));
        }
        return issued;
    }
}
