package lms.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Tenant của luồng hiện tại (thread-local). Không rò sang thread khác; việc nền phải tự chỉ rõ tenant qua runAs/callAs. */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    /** Phạm vi đọc tất cả tenant (ADR-0008); loại trừ với CURRENT. Chỉ để đọc: require() vẫn ném lỗi. */
    private static final ThreadLocal<Boolean> ALL = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Tenant cụ thể hiện tại; rỗng khi không có tenant hoặc đang ở phạm vi ALL. */
    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Tenant hiện tại, ném {@link TenantRequiredException} nếu chưa có hoặc đang ở phạm vi ALL (chặn mọi đường ghi). */
    public static UUID require() {
        UUID tenant = CURRENT.get();
        if (tenant == null) throw new TenantRequiredException();
        return tenant;
    }

    /** Đặt một tenant cụ thể (xóa phạm vi ALL); null thì xóa hết. */
    public static void set(UUID tenantId) {
        ALL.remove();
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
    }

    /** Đặt phạm vi đọc tất cả tenant (xóa tenant cụ thể). Chỉ interceptor đã xác nhận platform admin được gọi. */
    public static void bindAllTenants() {
        CURRENT.remove();
        ALL.set(Boolean.TRUE);
    }

    public static boolean isAllTenants() {
        return Boolean.TRUE.equals(ALL.get());
    }

    /**
     * Bộ lọc tenant cho truy vấn đọc: rỗng nghĩa là ALL (không lọc theo tenant), có giá trị là tenant cụ thể.
     * Ném {@link TenantRequiredException} khi không có tenant lẫn phạm vi ALL.
     */
    public static Optional<UUID> readFilter() {
        if (isAllTenants()) return Optional.empty();
        return Optional.of(require());
    }

    public static void clear() {
        CURRENT.remove();
        ALL.remove();
    }

    /** Chạy với tenant chỉ định, khôi phục phạm vi trước đó (kể cả ALL) kể cả khi ném lỗi. */
    public static void runAs(UUID tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAs(UUID tenantId, Supplier<T> action) {
        UUID previous = CURRENT.get();
        boolean previousAll = isAllTenants();
        set(tenantId);
        try {
            return action.get();
        } finally {
            if (previousAll) {
                bindAllTenants();
            } else {
                set(previous);
            }
        }
    }
}
