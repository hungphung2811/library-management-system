package lms.identity.auth.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lms.identity.tenant.application.TenantSummary;

/** Request/response của REST API xác thực. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotNull UUID userId,
            @NotBlank @Size(min = 3, max = 100) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            UUID departmentId,
            Boolean mustChangePassword) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    /** tenantCode: tùy chọn, mã tenant muốn vào (đường dẫn/QR của thư viện); bỏ trống thì chọn theo tenant dùng gần nhất. */
    public record LoginRequest(@NotBlank String username, @NotBlank String password, String tenantCode) {

        public LoginRequest(String username, String password) {
            this(username, password, null);
        }
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record SwitchTenantRequest(@NotNull UUID tenantId, @NotBlank String refreshToken) {
    }

    /** tenantId: tenant của phiên (null với phiên nền tảng); availableTenants: các tenant người dùng có thể đổi sang. */
    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                                boolean mustChangePassword, UUID tenantId, List<TenantSummary> availableTenants) {

        public TokenResponse {
            availableTenants = availableTenants == null ? List.of() : List.copyOf(availableTenants);
        }

        public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn) {
            return bearer(accessToken, refreshToken, expiresIn, false);
        }

        public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn,
                                           boolean mustChangePassword) {
            return bearer(accessToken, refreshToken, expiresIn, mustChangePassword, null, List.of());
        }

        public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn,
                                           boolean mustChangePassword, UUID tenantId,
                                           List<TenantSummary> availableTenants) {
            return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, mustChangePassword, tenantId,
                    availableTenants);
        }
    }
}
