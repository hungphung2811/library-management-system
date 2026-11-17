package lms.system.menu.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Một mục menu. {@code path == null} là nút nhóm (chỉ chứa con); {@code permissionKey == null}
 * nghĩa là mọi người đăng nhập đều thấy.
 * {@code module == null} là mục lõi (luôn hiện); khác null (LENDING/SALES) chỉ hiện khi tenant được bật module. Bất biến cây do {@link MenuTree} giữ, không phải lớp này.
 * {@code version} do tầng lưu trữ quản lý (khóa lạc quan), domain chỉ so sánh.
 */
public record MenuItem(
        UUID id,
        UUID tenantId,
        UUID parentId,
        String code,
        String label,
        String path,
        String icon,
        int sortOrder,
        boolean enabled,
        String permissionKey,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String module) {

    /** Mục lõi (không thuộc module nào). */
    public MenuItem(UUID id, UUID tenantId, UUID parentId, String code, String label, String path, String icon,
                    int sortOrder, boolean enabled, String permissionKey, long version, Instant createdAt,
                    Instant updatedAt) {
        this(id, tenantId, parentId, code, label, path, icon, sortOrder, enabled, permissionKey, version, createdAt,
                updatedAt, null);
    }

    public boolean isGroup() {
        return path == null;
    }
}
