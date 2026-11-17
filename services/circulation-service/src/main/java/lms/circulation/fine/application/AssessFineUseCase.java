package lms.circulation.fine.application;

import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineCalculator;
import lms.circulation.fine.domain.FineLine;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.policy.application.CirculationPolicyResponse;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lập phí phạt khi trả/báo mất (QĐ3, QĐ4, QĐ8). Idempotent theo (lượt mượn, bản sao, lý do): khoản đã có thì bỏ qua.
 * Chạy chung transaction với use case của borrowing gọi nó.
 * Tenant đã cấu hình chính sách thì phí trễ mỗi ngày và số ngày ân hạn lấy từ chính sách (chỉ cho khoản phạt lập
 * từ nay; khoản đã lập không tính lại); phí hỏng/mất vẫn theo tỉ lệ cấu hình chung.
 */
public class AssessFineUseCase {

    private final FineCalculator calculator;
    private final FineRepository repository;
    private final PublishFineEventPort eventPort;
    private final Clock clock;
    private final GetCirculationPolicyUseCase policies;

    public AssessFineUseCase(FineCalculator calculator, FineRepository repository, PublishFineEventPort eventPort,
                             Clock clock) {
        this(calculator, repository, eventPort, clock, GetCirculationPolicyUseCase.defaultsOnly());
    }

    public AssessFineUseCase(FineCalculator calculator, FineRepository repository, PublishFineEventPort eventPort,
                             Clock clock, GetCirculationPolicyUseCase policies) {
        this.policies = policies;
        this.calculator = calculator;
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public List<FineResponse> execute(AssessFineCommand command) {
        CirculationPolicyResponse policy = policies.execute();
        FineCalculator calc = policy.custom()
                ? calculator.withLateFeePerDay(BigDecimal.valueOf(policy.finePerDay())) : calculator;
        long overdueDays = policy.custom() ? Math.max(0, command.overdueDays() - policy.graceDays()) : command.overdueDays();
        List<FineLine> lines = switch (command.outcome()) {
            case LOST -> calc.assessLoss(command.coverPrice());
            case RETURNED_GOOD -> calc.assessReturn(overdueDays, command.coverPrice(), false);
            case RETURNED_DAMAGED -> calc.assessReturn(overdueDays, command.coverPrice(), true);
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
