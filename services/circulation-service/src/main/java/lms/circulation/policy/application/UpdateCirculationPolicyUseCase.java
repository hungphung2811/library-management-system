package lms.circulation.policy.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.policy.domain.CirculationPolicy;
import lms.circulation.policy.domain.CirculationPolicyRepository;
import lms.circulation.policy.domain.CirculationPolicySnapshot;
import lms.circulation.policy.domain.PolicyVersionConflictException;
import org.springframework.transaction.annotation.Transactional;

/** Upsert chính sách của tenant hiện tại, kiểm version (chưa có dòng thì version hợp lệ là 0). */
public class UpdateCirculationPolicyUseCase {

    private final CirculationPolicyRepository repository;
    private final Clock clock;

    public UpdateCirculationPolicyUseCase(CirculationPolicyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public CirculationPolicyResponse execute(UpdateCirculationPolicyRequest request, UUID updatedBy) {
        Optional<CirculationPolicySnapshot> current = repository.find();
        // Ba trường nhắc hạn thiếu trong request: giữ giá trị đang lưu (chưa lưu thì mặc định), client cũ không làm mất cấu hình.
        CirculationPolicy base = current.map(CirculationPolicySnapshot::policy).orElseGet(CirculationPolicy::defaults);
        CirculationPolicy policy = new CirculationPolicy(request.loanDays(), request.maxRenewals(),
                request.renewalDays(), request.maxActiveLoansPerReader(), request.finePerDay(), request.graceDays(),
                request.reservationHoldDays(),
                orElse(request.dueReminderDaysBefore(), base.dueReminderDaysBefore()),
                orElse(request.overdueReminderIntervalDays(), base.overdueReminderIntervalDays()),
                orElse(request.overdueReminderMax(), base.overdueReminderMax()));
        long expected = request.version() == null ? 0 : request.version();
        long actual = current.map(CirculationPolicySnapshot::version).orElse(0L);
        if (expected != actual) {
            throw new PolicyVersionConflictException(
                    "Chính sách đã được người khác cập nhật (version hiện tại " + actual + "), hãy tải lại rồi sửa");
        }
        return CirculationPolicyResponse.from(repository.save(policy, updatedBy, Instant.now(clock)));
    }

    private static int orElse(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
