package lms.identity.auth.infrastructure.web;

import lms.identity.auth.application.AuthDtos.ChangePasswordRequest;
import lms.identity.auth.application.AuthDtos.LoginRequest;
import lms.identity.auth.application.AuthDtos.RefreshRequest;
import lms.identity.auth.application.AuthDtos.RegisterRequest;
import lms.identity.auth.application.AuthDtos.SwitchTenantRequest;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.ChangePasswordUseCase;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.LogoutUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.application.SwitchTenantSessionUseCase;
import lms.security.CurrentUser;
import lms.security.JwtClaims;
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
    private final SwitchTenantSessionUseCase switchTenant;

    public AuthController(LoginUseCase login, RefreshTokenUseCase refresh, LogoutUseCase logout,
                          RegisterCredentialUseCase register, ChangePasswordUseCase changePassword,
                          SwitchTenantSessionUseCase switchTenant) {
        this.login = login;
        this.refresh = refresh;
        this.logout = logout;
        this.register = register;
        this.changePassword = changePassword;
        this.switchTenant = switchTenant;
    }

    @PublicEndpoint
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return login.execute(request.username(), request.password(), request.tenantCode());
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

    /**
     * Đổi tenant đang dùng: chỉ cần đăng nhập (token không có tenantId, như của platform admin, vẫn gọi được); quyền
     * ở tenant đích do context tenant kiểm tra. Body mang refresh token hiện tại để thu hồi. Trả cặp token mới của tenant đó.
     */
    @AuthenticatedOnly
    @PostMapping("/switch-tenant")
    public TokenResponse switchTenant(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SwitchTenantRequest request) {
        return switchTenant.execute(CurrentUser.from(jwt).userId(), request.tenantId(), request.refreshToken());
    }

    /**
     * Cần permission USER_CREATE. userId do user-service cấp; auth-service chỉ giữ credential. Tài khoản mới là thành
     * viên của tenant trong token người tạo.
     */
    @PostMapping("/credentials")
    public ResponseEntity<Map<String, UUID>> register(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody RegisterRequest request) {
        UUID userId = register.execute(request.userId(), request.username(), request.password(), request.departmentId(),
                Boolean.TRUE.equals(request.mustChangePassword()), creatorTenant(jwt));
        return ResponseEntity.created(URI.create("/internal/auth/credentials/" + userId)).body(Map.of("userId", userId));
    }

    /** Claim tenantId của người tạo (null với token không thuộc tenant nào, ví dụ của platform admin). */
    private static UUID creatorTenant(Jwt jwt) {
        String value = jwt == null ? null : jwt.getClaimAsString(JwtClaims.TENANT_ID);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
