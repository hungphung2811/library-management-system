package lms.circulation.policy.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lms.circulation.policy.domain.CompensationPolicy;
import lms.circulation.policy.domain.CompensationPolicyRepository;
import lms.circulation.policy.domain.CompensationPolicySnapshot;
import lms.circulation.policy.domain.PolicyVersionConflictException;
import org.springframework.transaction.annotation.Transactional;

/** Upsert chính sách đền bù của tenant hiện tại, kiểm version (chưa có dòng thì version hợp lệ là 0). */
public class UpdateCompensationPolicyUseCase {

    private final CompensationPolicyRepository repository;
    private final Clock clock;

    public UpdateCompensationPolicyUseCase(CompensationPolicyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public CompensationPolicyResponse execute(UpdateCompensationPolicyRequest request, UUID updatedBy) {
        CompensationPolicy policy = new CompensationPolicy(request.lostRate(), request.priceSource(),
                request.minAmount(), request.maxAmount(), request.foundWindowDays(), request.refundProcessingFee());
        long expected = request.version() == null ? 0 : request.version();
        long actual = repository.find().map(CompensationPolicySnapshot::version).orElse(0L);
        if (expected != actual) {
            throw new PolicyVersionConflictException(
                    "Chính sách đền bù đã được người khác cập nhật (version hiện tại " + actual + "), hãy tải lại rồi sửa");
        }
        return CompensationPolicyResponse.from(repository.save(policy, updatedBy, Instant.now(clock)));
    }
}
