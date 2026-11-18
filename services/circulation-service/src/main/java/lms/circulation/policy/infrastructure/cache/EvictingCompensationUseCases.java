package lms.circulation.policy.infrastructure.cache;

import java.time.Clock;
import java.util.UUID;
import lms.cache.RequestCache;
import lms.circulation.policy.application.CompensationPolicyResponse;
import lms.circulation.policy.application.CreateDamageLevelRequest;
import lms.circulation.policy.application.CreateDamageLevelUseCase;
import lms.circulation.policy.application.DamageLevelResponse;
import lms.circulation.policy.application.DamageLevelUsagePort;
import lms.circulation.policy.application.DeleteDamageLevelUseCase;
import lms.circulation.policy.application.UpdateCompensationPolicyRequest;
import lms.circulation.policy.application.UpdateCompensationPolicyUseCase;
import lms.circulation.policy.application.UpdateDamageLevelRequest;
import lms.circulation.policy.application.UpdateDamageLevelUseCase;
import lms.circulation.policy.domain.CompensationPolicyRepository;
import lms.circulation.policy.domain.DamageLevelRepository;
import org.springframework.transaction.annotation.Transactional;

/** Các use case ghi chính sách đền bù/bậc hỏng kèm evict cache GET (RequestCache hoãn việc xóa tới sau commit). */
public final class EvictingCompensationUseCases {

    private EvictingCompensationUseCases() {
    }

    public static class UpdatePolicy extends UpdateCompensationPolicyUseCase {
        private final RequestCache cache;

        public UpdatePolicy(CompensationPolicyRepository repository, Clock clock, RequestCache cache) {
            super(repository, clock);
            this.cache = cache;
        }

        @Override
        @Transactional
        public CompensationPolicyResponse execute(UpdateCompensationPolicyRequest request, UUID updatedBy) {
            CompensationPolicyResponse result = super.execute(request, updatedBy);
            cache.evict(CompensationCache.POLICY_NAMESPACE, CompensationCache.KEY);
            return result;
        }
    }

    public static class CreateLevel extends CreateDamageLevelUseCase {
        private final RequestCache cache;

        public CreateLevel(DamageLevelRepository repository, Clock clock, RequestCache cache) {
            super(repository, clock);
            this.cache = cache;
        }

        @Override
        @Transactional
        public DamageLevelResponse execute(CreateDamageLevelRequest request, UUID updatedBy) {
            DamageLevelResponse result = super.execute(request, updatedBy);
            cache.evict(CompensationCache.LEVELS_NAMESPACE, CompensationCache.KEY);
            return result;
        }
    }

    public static class UpdateLevel extends UpdateDamageLevelUseCase {
        private final RequestCache cache;

        public UpdateLevel(DamageLevelRepository repository, Clock clock, RequestCache cache) {
            super(repository, clock);
            this.cache = cache;
        }

        @Override
        @Transactional
        public DamageLevelResponse execute(String code, UpdateDamageLevelRequest request, UUID updatedBy) {
            DamageLevelResponse result = super.execute(code, request, updatedBy);
            cache.evict(CompensationCache.LEVELS_NAMESPACE, CompensationCache.KEY);
            return result;
        }
    }

    public static class DeleteLevel extends DeleteDamageLevelUseCase {
        private final RequestCache cache;

        public DeleteLevel(DamageLevelRepository repository, DamageLevelUsagePort usage, RequestCache cache) {
            super(repository, usage);
            this.cache = cache;
        }

        @Override
        @Transactional
        public void execute(String code) {
            super.execute(code);
            cache.evict(CompensationCache.LEVELS_NAMESPACE, CompensationCache.KEY);
        }
    }
}
