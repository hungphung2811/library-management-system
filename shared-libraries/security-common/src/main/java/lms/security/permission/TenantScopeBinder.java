package lms.security.permission;

import java.util.UUID;

/**
 * SPI để {@link PermissionInterceptor} gắn phạm vi tenant cho request đọc xuyên tenant mà không phụ thuộc
 * tenant-common (ADR-0008). Cài đặt thật nằm ở tenant-common; mặc định là no-op ({@link #NOOP}).
 */
public interface TenantScopeBinder {

    /** Phạm vi đọc tất cả tenant (chỉ đọc). */
    void bindAll();

    /** Phạm vi một tenant cụ thể do platform admin chọn. */
    void bindSingle(UUID tenantId);

    TenantScopeBinder NOOP = new TenantScopeBinder() {
        @Override
        public void bindAll() {
        }

        @Override
        public void bindSingle(UUID tenantId) {
        }
    };
}
