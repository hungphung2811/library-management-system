package lms.identity.tenant.application;

import jakarta.validation.Valid;
import java.util.UUID;

/** Thêm quản trị viên tenant: đúng một trong newAdmin (tạo tài khoản mới) hoặc existingUserId (tài khoản có sẵn). */
public record TenantAdminRequest(@Valid NewAdmin newAdmin, UUID existingUserId) {
}
