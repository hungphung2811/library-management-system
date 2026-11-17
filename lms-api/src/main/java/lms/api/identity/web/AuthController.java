package lms.api.identity.web;

import lms.api.identity.client.AuthClient;
import lms.api.identity.dto.AuthDtos.ChangePasswordRequest;
import lms.api.identity.dto.AuthDtos.CredentialCreated;
import lms.api.identity.dto.AuthDtos.LoginRequest;
import lms.api.identity.dto.AuthDtos.PasswordResetConfirmRequest;
import lms.api.identity.dto.AuthDtos.PasswordResetRequest;
import lms.api.identity.dto.AuthDtos.RefreshRequest;
import lms.api.identity.dto.AuthDtos.RegisterCredentialRequest;
import lms.api.identity.dto.AuthDtos.SwitchTenantRequest;
import lms.api.identity.dto.AuthDtos.TokenView;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API xác thực cho FE. login/refresh/logout là công khai (xem SecurityConfiguration); đổi tenant, đổi mật khẩu và tạo
 * credential cần token, quyền do identity-service kiểm. Token trả về luôn kèm Cache-Control: no-store.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthClient auth;

    public AuthController(AuthClient auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenView> login(@Valid @RequestBody LoginRequest request) {
        return noStore(auth.login(request.username(), request.password(), request.tenantCode()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenView> refresh(@Valid @RequestBody RefreshRequest request) {
        return noStore(auth.refresh(request.refreshToken()));
    }

    /** Đổi tenant đang dùng (body {tenantId, refreshToken}); identity kiểm quyền ở tenant đích và thu hồi token cũ. */
    @PostMapping("/switch-tenant")
    public ResponseEntity<TokenView> switchTenant(@Valid @RequestBody SwitchTenantRequest request) {
        return noStore(auth.switchTenant(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** Quên mật khẩu (công khai): luôn 202 dù tài khoản có tồn tại hay không. */
    @PostMapping("/password-reset-requests")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        auth.requestPasswordReset(request.username());
        return ResponseEntity.accepted().build();
    }

    /** Đặt lại mật khẩu bằng token một lần (công khai): 204; 400 INVALID_RESET_TOKEN khi token sai/hết hạn/đã dùng. */
    @PostMapping("/password-reset-confirmations")
    public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        auth.confirmPasswordReset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        auth.changePassword(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/credentials")
    public ResponseEntity<CredentialCreated> register(@Valid @RequestBody RegisterCredentialRequest request) {
        CredentialCreated created = auth.register(request);
        return ResponseEntity.created(URI.create("/api/auth/credentials/" + created.userId())).body(created);
    }

    static ResponseEntity<TokenView> noStore(TokenView token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(token);
    }
}
