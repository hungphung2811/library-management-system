package lms.demo.runner;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Chốt chặn trước khi seed (ADR-0018 mục 2.4): từ chối, không ghi gì, khi profile production đang bật, thiếu cờ
 * {@code lms.demo-seed.enabled=true}, thiếu mật khẩu demo, hoặc thiếu tenant đích ở service không tự tạo tenant.
 */
public final class DemoSeedGuard {

    static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private DemoSeedGuard() {
    }

    /**
     * @param createsTenant true ở identity (tự tạo tenant demo nên không cần {@code tenant-id})
     * @return tenant đích, hoặc {@code null} khi {@code createsTenant}
     * @throws DemoSeedRefusedException khi bị từ chối
     */
    public static UUID check(Collection<String> activeProfiles, boolean enabled, String tenantId, boolean createsTenant,
                             String password) {
        for (String profile : activeProfiles) {
            if (profile != null && PRODUCTION_PROFILES.contains(profile.trim().toLowerCase(Locale.ROOT))) {
                throw new DemoSeedRefusedException("Từ chối seed dữ liệu demo: profile production '" + profile + "' đang bật");
            }
        }
        if (!enabled) {
            throw new DemoSeedRefusedException("Từ chối seed dữ liệu demo: thiếu lms.demo-seed.enabled=true");
        }
        if (password == null || password.isBlank()) {
            throw new DemoSeedRefusedException("Từ chối seed dữ liệu demo: thiếu lms.demo-seed.password");
        }
        if (createsTenant) {
            return null;
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new DemoSeedRefusedException("Từ chối seed dữ liệu demo: thiếu lms.demo-seed.tenant-id");
        }
        try {
            return UUID.fromString(tenantId.trim());
        } catch (IllegalArgumentException e) {
            throw new DemoSeedRefusedException("Từ chối seed dữ liệu demo: lms.demo-seed.tenant-id không phải UUID");
        }
    }
}
