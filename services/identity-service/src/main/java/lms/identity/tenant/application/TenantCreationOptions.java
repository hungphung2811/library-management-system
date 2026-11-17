package lms.identity.tenant.application;

import java.util.UUID;

/**
 * Tùy chọn khi tạo tenant ngoài luồng API thường: id tenant và id quản trị viên cố định (null = tự sinh), cờ buộc đổi
 * mật khẩu của quản trị viên mới. Chỉ dùng cho dữ liệu demo (ADR-0018) để id khớp giữa các service; API platform luôn
 * dùng {@link #STANDARD}.
 */
public record TenantCreationOptions(UUID tenantId, UUID adminUserId, boolean adminMustChangePassword) {

    public static final TenantCreationOptions STANDARD = new TenantCreationOptions(null, null, true);
}
