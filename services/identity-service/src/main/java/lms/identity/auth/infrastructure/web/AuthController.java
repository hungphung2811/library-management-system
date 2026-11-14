package lms.identity.auth.infrastructure.web;

import lms.identity.auth.application.AuthDtos.ChangePasswordRequest;
import lms.identity.auth.application.AuthDtos.LoginRequest;
import lms.identity.auth.application.AuthDtos.RefreshRequest;
import lms.identity.auth.application.AuthDtos.RegisterRequest;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.ChangePasswordUseCase;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.LogoutUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.security.CurrentUser;
import lms.security.permission.AuthenticatedOnly;
import lms.security.permission.PublicEndpoint;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth")
public class AuthController {

    private final LoginUseCase login;
    private final RefreshTokenUseCase refresh;
    private final LogoutUseCase logout;
    private final RegisterCredentialUseCase register;
    private final ChangePasswordUseCase changePassword;

    public AuthController(LoginUseCase login, RefreshTokenUseCase refresh, LogoutUseCase logout,
                          RegisterCredentialUseCase register, ChangePasswordUseCase changePassword) {
        this.login = login;
        this.refresh = refresh;
        this.logout = logout;
        this.register = register;
        this.changePassword = changePassword;
    }

    @PublicEndpoint
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return login.execute(request.username(), request.password());
    }

    @PublicEndpoint
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return refresh.execute(request.refreshToken());
    }

    @PublicEndpoint
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        logout.execute(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** UC01: người dùng đang đăng nhập tự đổi mật khẩu (chỉ cần token hợp lệ, kể cả token của tài khoản phải đổi mật khẩu). */
    @AuthenticatedOnly
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        changePassword.execute(CurrentUser.from(jwt).userId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** Cần permission USER_CREATE. userId do user-service cấp; auth-service chỉ giữ credential. */
    @PostMapping("/credentials")
    public ResponseEntity<Map<String, UUID>> register(@Valid @RequestBody RegisterRequest request) {
        UUID userId = register.execute(request.userId(), request.username(), request.password(), request.departmentId(),
                Boolean.TRUE.equals(request.mustChangePassword()));
        return ResponseEntity.created(URI.create("/internal/auth/credentials/" + userId)).body(Map.of("userId", userId));
    }
}
