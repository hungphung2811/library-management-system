package lms.circulation.policy.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.policy.domain.CompensationPolicy;
import lms.circulation.policy.domain.CompensationPolicyRepository;
import lms.circulation.policy.domain.CompensationPolicySnapshot;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc chính sách đền bù của tenant hiện tại; chưa cấu hình thì trả mặc định (custom=false). Các use case nghiệp vụ lập
 * phạt và hoàn khoản dùng chính bean này (đọc DB, không qua cache) để luôn thấy chính sách mới nhất.
 */
public class GetCompensationPolicyUseCase {

    private final CompensationPolicyRepository repository;

    public GetCompensationPolicyUseCase(CompensationPolicyRepository repository) {
        this.repository = repository;
    }

    /** Bản không đọc DB, luôn trả mặc định (dùng cho test và nơi chưa nối persistence). */
    public static GetCompensationPolicyUseCase defaultsOnly() {
        return new GetCompensationPolicyUseCase(new CompensationPolicyRepository() {
            @Override
            public Optional<CompensationPolicySnapshot> find() {
                return Optional.empty();
            }

            @Override
            public CompensationPolicySnapshot save(CompensationPolicy policy, UUID updatedBy, Instant now) {
                throw new UnsupportedOperationException();
            }
        });
    }

    @Transactional(readOnly = true)
    public CompensationPolicyResponse execute() {
        return repository.find().map(CompensationPolicyResponse::from).orElseGet(CompensationPolicyResponse::defaults);
    }
}
