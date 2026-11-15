package lms.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Tenant của luồng hiện tại (thread-local). Không rò sang thread khác; việc nền phải tự chỉ rõ tenant qua runAs/callAs. */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Tenant hiện tại, ném {@link TenantRequiredException} nếu chưa có. */
    public static UUID require() {
        UUID tenant = CURRENT.get();
        if (tenant == null) throw new TenantRequiredException();
        return tenant;
    }

    public static void set(UUID tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Chạy với tenant chỉ định, khôi phục giá trị trước đó kể cả khi ném lỗi. */
    public static void runAs(UUID tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAs(UUID tenantId, Supplier<T> action) {
        UUID previous = CURRENT.get();
        set(tenantId);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }
}
