package lms.circulation.borrowing.application;

import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.BorrowingMapper;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingNotFoundException;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.policy.application.CirculationPolicyResponse;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO: kiểm tra yêu cầu đặt trước (Reservation Service) trước khi cho gia hạn.
 * Tenant đã cấu hình chính sách thì số lần gia hạn tối đa và số ngày mỗi lần lấy từ chính sách tại thời điểm gia hạn
 * (phiếu cũ cũng theo giá trị mới, vì đây là thao tác mới); chưa cấu hình thì giữ hành vi mặc định của phiếu.
 */
public class RenewBorrowingUseCase {

    private final BorrowingRepository repository;
    private final SaveBorrowingPort savePort;
    private final Clock clock;
    private final GetCirculationPolicyUseCase policies;

    public RenewBorrowingUseCase(BorrowingRepository repository, SaveBorrowingPort savePort, Clock clock) {
        this(repository, savePort, clock, GetCirculationPolicyUseCase.defaultsOnly());
    }

    public RenewBorrowingUseCase(BorrowingRepository repository, SaveBorrowingPort savePort, Clock clock,
                                 GetCirculationPolicyUseCase policies) {
        this.policies = policies;
        this.repository = repository;
        this.savePort = savePort;
        this.clock = clock;
    }

    @Transactional
    public BorrowingResponse execute(UUID borrowingId) {
        BorrowingRecord record = repository.findById(new BorrowingId(borrowingId))
                .orElseThrow(() -> new BorrowingNotFoundException(borrowingId));
        CirculationPolicyResponse policy = policies.execute();
        if (policy.custom()) {
            record.renew(LocalDate.now(clock), policy.maxRenewals(), policy.renewalDays());
        } else {
            record.renew(LocalDate.now(clock));
        }
        return BorrowingMapper.toResponse(savePort.save(record));
    }
}
