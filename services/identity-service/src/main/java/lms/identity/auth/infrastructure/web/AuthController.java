package lms.identity.auth.infrastructure.web;

import lms.identity.auth.application.AuthDtos.ChangePasswordRequest;
import lms.identity.auth.application.AuthDtos.LoginRequest;
import lms.identity.auth.application.AuthDtos.PasswordResetConfirmRequest;
import lms.identity.auth.application.AuthDtos.PasswordResetRequest;
import lms.identity.auth.application.ConfirmPasswordResetUseCase;
import lms.identity.auth.application.RequestPasswordResetUseCase;
import lms.identity.auth.application.AuthDtos.RefreshRequest;
import lms.identity.auth.application.AuthDtos.RegisterRequest;
import lms.identity.auth.application.AuthDtos.SignupRequest;
import lms.identity.auth.application.AuthDtos.SwitchTenantRequest;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.ChangePasswordUseCase;
import lms.identity.auth.application.ClientInfo;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.LogoutUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.application.SignupUseCase;
import lms.identity.auth.application.SwitchTenantSessionUseCase;
import lms.identity.auth.domain.TokenChannel;
import lms.security.CurrentUser;
import lms.security.JwtClaims;
import lms.security.permission.AuthenticatedOnly;
import lms.security.permission.PublicEndpoint;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth")
public class AuthController {

    /** Header BFF (lms-api, customer-api) chuyển IP và user-agent của client ở các lời gọi phát token (ADR-0017). */
    static final String CLIENT_IP_HEADER = "X-Client-Ip";
    static final String CLIENT_USER_AGENT_HEADER = "X-Client-User-Agent";

    private final LoginUseCase login;
    private final RefreshTokenUseCase refresh;
    private final LogoutUseCase logout;
    private final RegisterCredentialUseCase register;
    private final ChangePasswordUseCase changePassword;
    private final SwitchTenantSessionUseCase switchTenant;
    private final SignupUseCase signup;
    private final RequestPasswordResetUseCase requestReset;
    private final ConfirmPasswordResetUseCase confirmReset;

    public AuthController(LoginUseCase login, RefreshTokenUseCase refresh, LogoutUseCase logout,
                          RegisterCredentialUseCase register, ChangePasswordUseCase changePassword,
                          SwitchTenantSessionUseCase switchTenant, SignupUseCase signup,
                          RequestPasswordResetUseCase requestReset, ConfirmPasswordResetUseCase confirmReset) {
        this.login = login;
        this.refresh = refresh;
        this.logout = logout;
        this.register = register;
        this.changePassword = changePassword;
        this.switchTenant = switchTenant;
        this.signup = signup;
        this.requestReset = requestReset;
        this.confirmReset = confirmReset;
    }

    @PublicEndpoint
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request,
                               @RequestHeader(value = CLIENT_IP_HEADER, required = false) String clientIp,
                               @RequestHeader(value = CLIENT_USER_AGENT_HEADER, required = false) String userAgent) {
        return login.execute(request.username(), request.password(), request.tenantCode(),
                TokenChannel.parse(request.channel()), ClientInfo.of(clientIp, userAgent));
    }

    /**
     * Tự đăng ký của khách (ADR-0010 B2.1). Công khai nhưng KHÔNG nhận role/flag/tenantId nào: body là allowlist
     * {@link SignupRequest}, role do tenant quyết (signup_default). 403 SIGNUP_NOT_AVAILABLE là mã chung duy nhất cho
     * mọi trường hợp không đăng ký được. Thành công: 201 không thân.
     */
    @PublicEndpoint
    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request) {
        signup.execute(new SignupUseCase.Command(request.tenantCode(), request.username(), request.password(),
                request.fullName(), request.email()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PublicEndpoint
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request,
                                 @RequestHeader(value = CLIENT_IP_HEADER, required = false) String clientIp,
                                 @RequestHeader(value = CLIENT_USER_AGENT_HEADER, required = false) String userAgent) {
        return refresh.execute(request.refreshToken(), TokenChannel.parse(request.channel()), request.tenantId(),
                ClientInfo.of(clientIp, userAgent));
    }

    @PublicEndpoint
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        logout.execute(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Quên mật khẩu, yêu cầu. Công khai; LUÔN 202 dù tài khoản có tồn tại hay không (chống dò tài khoản), kể cả khi vượt
     * giới hạn tần suất theo tài khoản.
     */
    @PublicEndpoint
    @PostMapping("/password-reset-requests")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        requestReset.execute(request.username());
        return ResponseEntity.accepted().build();
    }

    /** Quên mật khẩu, xác nhận bằng token một lần. Công khai; 204 khi thành công, 400 INVALID_RESET_TOKEN khi sai/hết hạn/đã dùng. */
    @PublicEndpoint
    @PostMapping("/password-reset-confirmations")
    public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        confirmReset.execute(request.token(), request.newPassword());
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
    public TokenResponse switchTenant(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SwitchTenantRequest request,
                                      @RequestHeader(value = CLIENT_IP_HEADER, required = false) String clientIp,
                                      @RequestHeader(value = CLIENT_USER_AGENT_HEADER, required = false)
                                      String userAgent) {
        return switchTenant.execute(CurrentUser.from(jwt).userId(), request.tenantId(), request.refreshToken(),
                ClientInfo.of(clientIp, userAgent));
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
