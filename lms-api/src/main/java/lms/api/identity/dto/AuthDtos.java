package lms.api.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Hợp đồng FE cho xác thực (đăng nhập, làm mới, đổi mật khẩu, tạo credential, đăng nhập ngoài). */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
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

    public record TokenView(String accessToken, String refreshToken, String tokenType, long expiresIn,
                            boolean mustChangePassword) {
    }

    public record CredentialCreated(UUID userId) {
    }

    public record ProviderView(String name, String displayName) {
    }

    public record ExternalLoginView(String provider, String email, Instant linkedAt, Instant lastUsedAt) {
    }
}
