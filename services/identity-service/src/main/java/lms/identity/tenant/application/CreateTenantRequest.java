package lms.identity.tenant.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Tạo tenant: đúng một trong {@code newAdmin} (tài khoản mới, mật khẩu tạm) hoặc {@code existingUserId} (gắn tài khoản
 * có sẵn) làm quản trị viên đầu tiên. organizationId tùy chọn. modules (ADR-0009): module bật ban đầu; bỏ trống (null)
 * = [LENDING]; danh sách rỗng bị từ chối (400).
 */
public record CreateTenantRequest(
        @NotBlank @Size(min = 2, max = 50) String code,
        @NotBlank @Size(max = 255) String name,
        UUID organizationId,
        @Valid NewAdmin newAdmin,
        UUID existingUserId,
        List<String> modules) {

    /** Không nêu module: mặc định [LENDING]. */
    public CreateTenantRequest(String code, String name, UUID organizationId, NewAdmin newAdmin, UUID existingUserId) {
        this(code, name, organizationId, newAdmin, existingUserId, null);
    }
}
