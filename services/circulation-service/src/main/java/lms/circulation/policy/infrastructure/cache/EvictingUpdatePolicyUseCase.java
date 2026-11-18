package lms.circulation.policy.infrastructure.cache;

import java.time.Clock;
import java.util.UUID;
import lms.cache.RequestCache;
import lms.circulation.policy.application.CirculationPolicyResponse;
import lms.circulation.policy.application.UpdateCirculationPolicyRequest;
import lms.circulation.policy.application.UpdateCirculationPolicyUseCase;
import lms.circulation.policy.domain.CirculationPolicyRepository;
import org.springframework.transaction.annotation.Transactional;

/** Ghi chính sách rồi evict cache GET (RequestCache hoãn việc xóa tới sau commit). */
public class EvictingUpdatePolicyUseCase extends UpdateCirculationPolicyUseCase {

    private final RequestCache cache;

    public EvictingUpdatePolicyUseCase(CirculationPolicyRepository repository, Clock clock, RequestCache cache) {
        super(repository, clock);
        this.cache = cache;
    }

    @Override
    @Transactional
    public CirculationPolicyResponse execute(UpdateCirculationPolicyRequest request, UUID updatedBy) {
        CirculationPolicyResponse result = super.execute(request, updatedBy);
        cache.evict(PolicyCache.NAMESPACE, PolicyCache.KEY);
        return result;
    }
}
