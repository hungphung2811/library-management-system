package lms.circulation.policy.infrastructure.cache;

import lms.cache.RequestCache;
import lms.circulation.policy.application.CompensationPolicyResponse;
import lms.circulation.policy.application.DamageLevelListResponse;
import lms.circulation.policy.application.GetCompensationPolicyUseCase;
import lms.circulation.policy.application.ListDamageLevelsUseCase;

/**
 * Đọc chính sách đền bù và danh sách bậc hỏng có cache cho các endpoint GET. Cố ý KHÔNG phải subclass của use case gốc:
 * use case lập phạt/hoàn khoản inject bean gốc (đọc DB) nên luôn thấy cấu hình mới nhất.
 */
public class CachedCompensationReader {

    private final GetCompensationPolicyUseCase policy;
    private final ListDamageLevelsUseCase levels;
    private final RequestCache cache;

    public CachedCompensationReader(GetCompensationPolicyUseCase policy, ListDamageLevelsUseCase levels,
                                    RequestCache cache) {
        this.policy = policy;
        this.levels = levels;
        this.cache = cache;
    }

    public CompensationPolicyResponse policy() {
        return cache.get(CompensationCache.POLICY_NAMESPACE, CompensationCache.KEY, CompensationCache.TTL,
                CompensationPolicyResponse.class, policy::execute);
    }

    public DamageLevelListResponse levels() {
        return cache.get(CompensationCache.LEVELS_NAMESPACE, CompensationCache.KEY, CompensationCache.TTL,
                DamageLevelListResponse.class, levels::execute);
    }
}
