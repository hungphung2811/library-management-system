package lms.identity.auth.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

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

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                                boolean mustChangePassword) {

        public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn) {
            return bearer(accessToken, refreshToken, expiresIn, false);
        }

        public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn,
                                           boolean mustChangePassword) {
            return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, mustChangePassword);
        }
    }
}
