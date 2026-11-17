package lms.circulation.policy.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.policy.domain.CirculationPolicy;
import lms.circulation.policy.domain.CirculationPolicyRepository;
import lms.circulation.policy.domain.CirculationPolicySnapshot;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc chính sách mượn trả của tenant hiện tại; chưa cấu hình thì trả mặc định (custom=false). Các context mượn,
 * phạt, đặt trước dùng use case này (tầng application) để lấy chính sách.
 */
public class GetCirculationPolicyUseCase {

    private final CirculationPolicyRepository repository;

    public GetCirculationPolicyUseCase(CirculationPolicyRepository repository) {
        this.repository = repository;
    }

    /** Bản không đọc DB, luôn trả mặc định (dùng cho test và nơi chưa nối persistence). */
    public static GetCirculationPolicyUseCase defaultsOnly() {
        return new GetCirculationPolicyUseCase(new CirculationPolicyRepository() {
            @Override
            public Optional<CirculationPolicySnapshot> find() {
                return Optional.empty();
            }

            @Override
            public CirculationPolicySnapshot save(CirculationPolicy policy, UUID updatedBy, Instant now) {
                throw new UnsupportedOperationException();
            }
        });
    }

    @Transactional(readOnly = true)
    public CirculationPolicyResponse execute() {
        return repository.find().map(CirculationPolicyResponse::from).orElseGet(CirculationPolicyResponse::defaults);
    }
}
