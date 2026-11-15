package lms.identity.tenant.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Sửa tenant: tên và trường (organizationId null = bỏ trường). Không đổi được {@code code}. */
public record UpdateTenantRequest(@NotBlank @Size(max = 255) String name, UUID organizationId) {
}
