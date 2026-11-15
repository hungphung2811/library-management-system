package lms.api.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hợp đồng FE cho xác thực (đăng nhập, làm mới, đổi tenant, đổi mật khẩu, tạo credential, đăng nhập ngoài). Khớp
 * AuthDtos của identity-service (multi-tenant, ADR-0006).
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** tenantCode: tùy chọn, mã tenant muốn vào; bỏ trống thì identity chọn theo tenant dùng gần nhất. */
    public record LoginRequest(@NotBlank String username, @NotBlank String password, String tenantCode) {

        public LoginRequest(String username, String password) {
            this(username, password, null);
        }
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** Đổi tenant đang dùng: refreshToken hiện tại bắt buộc (identity thu hồi nó). */
    public record SwitchTenantRequest(@NotNull UUID tenantId, @NotBlank String refreshToken) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record RegisterCredentialRequest(@NotNull UUID userId,
                                            @NotBlank @Size(min = 3, max = 100) String username,
                                            @NotBlank @Size(min = 8, max = 72) String password,
                                            UUID departmentId, Boolean mustChangePassword) {
    }

    public record ExchangeRequest(@NotBlank String code) {
    }

    /** tenantId: tenant của phiên (null với phiên nền tảng); availableTenants: các tenant người dùng có thể đổi sang. */
    public record TokenView(String accessToken, String refreshToken, String tokenType, long expiresIn,
                            boolean mustChangePassword, UUID tenantId, List<TenantView> availableTenants) {

        public TokenView {
            availableTenants = availableTenants == null ? List.of() : List.copyOf(availableTenants);
        }

        public TokenView(String accessToken, String refreshToken, String tokenType, long expiresIn,
                         boolean mustChangePassword) {
            this(accessToken, refreshToken, tokenType, expiresIn, mustChangePassword, null, List.of());
        }
    }

    /** Tenant người dùng truy cập được (TenantSummary của identity). */
    public record TenantView(UUID id, String code, String name) {
    }

    public record CredentialCreated(UUID userId) {
    }

    public record ProviderView(String name, String displayName) {
    }

    public record ExternalLoginView(String provider, String email, Instant linkedAt, Instant lastUsedAt) {
    }
}
