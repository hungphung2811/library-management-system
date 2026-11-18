package lms.circulation.policy.infrastructure.cache;

import java.time.Duration;

/**
 * Hằng số cache chính sách đền bù và bậc hỏng; chỉ các đường GET của controller dùng cache (TTL 60 giây, ghi thì evict
 * sau commit). Use case nghiệp vụ lập phạt/hoàn khoản dùng bean gốc, không qua cache.
 */
public final class CompensationCache {

    public static final String POLICY_NAMESPACE = "circulation.compensation-policy";
    public static final String LEVELS_NAMESPACE = "circulation.damage-levels";
    public static final String KEY = "current";
    public static final Duration TTL = Duration.ofSeconds(60);

    private CompensationCache() {
    }
}
