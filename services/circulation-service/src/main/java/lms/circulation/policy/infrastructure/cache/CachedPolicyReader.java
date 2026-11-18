package lms.circulation.policy.infrastructure.cache;

import lms.cache.RequestCache;
import lms.circulation.policy.application.CirculationPolicyResponse;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;

/**
 * Đọc chính sách có cache cho endpoint GET. Cố ý KHÔNG phải subclass của GetCirculationPolicyUseCase: các use case
 * mượn/gia hạn/phạt/đặt trước inject chính use case gốc (đọc DB) nên luôn thấy chính sách mới nhất.
 */
public class CachedPolicyReader {

    private final GetCirculationPolicyUseCase delegate;
    private final RequestCache cache;

    public CachedPolicyReader(GetCirculationPolicyUseCase delegate, RequestCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    public CirculationPolicyResponse get() {
        return cache.get(PolicyCache.NAMESPACE, PolicyCache.KEY, PolicyCache.TTL, CirculationPolicyResponse.class,
                delegate::execute);
    }
}
