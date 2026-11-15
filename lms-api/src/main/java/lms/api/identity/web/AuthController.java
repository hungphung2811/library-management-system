package lms.api.identity.web;

import lms.api.identity.client.AuthClient;
import lms.api.identity.dto.AuthDtos.ChangePasswordRequest;
import lms.api.identity.dto.AuthDtos.CredentialCreated;
import lms.api.identity.dto.AuthDtos.LoginRequest;
import lms.api.identity.dto.AuthDtos.RefreshRequest;
import lms.api.identity.dto.AuthDtos.RegisterCredentialRequest;
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
 * API xác thực cho FE. login/refresh/logout là công khai (xem SecurityConfiguration); đổi mật khẩu và tạo credential
 * cần token, quyền do identity-service kiểm. Token trả về luôn kèm Cache-Control: no-store.
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
        return noStore(auth.login(request.username(), request.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenView> refresh(@Valid @RequestBody RefreshRequest request) {
        return noStore(auth.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
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
