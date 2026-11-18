package lms.circulation.policy.infrastructure.cache;

import java.time.Duration;

/** Hằng số cache chính sách lưu thông; chỉ đường GET /internal/circulation/policy dùng cache. */
public final class PolicyCache {

    public static final String NAMESPACE = "circulation.policy";
    public static final String KEY = "current";
    public static final Duration TTL = Duration.ofSeconds(60);

    private PolicyCache() {
    }
}
