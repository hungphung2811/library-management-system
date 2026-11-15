package lms.circulation.fine.application;

import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** UC09 - Thu phí phạt: thu mọi khoản chưa thanh toán của một độc giả trong một transaction. */
public class CollectFinesUseCase {

    private final FineRepository repository;
    private final PublishFineEventPort eventPort;
    private final Clock clock;

    public CollectFinesUseCase(FineRepository repository, PublishFineEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public CollectFinesResponse execute(UUID readerId) {
        List<Fine> unpaid = repository.findUnpaidByReaderForUpdate(readerId);
        Instant now = Instant.now(clock);
        BigDecimal total = BigDecimal.ZERO;
        for (Fine fine : unpaid) {
            fine.pay(now);
            repository.save(fine);
            fine.pullEvents().forEach(eventPort::publish);
            total = total.add(fine.amount());
        }
        return new CollectFinesResponse(readerId, unpaid.size(), total, unpaid.stream().map(FineResponse::from).toList());
    }

    public record CollectFinesResponse(UUID readerId, int collectedCount, BigDecimal totalCollected,
                                       List<FineResponse> fines) {
    }
}
