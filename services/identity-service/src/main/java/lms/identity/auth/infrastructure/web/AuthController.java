package lms.identity.auth.infrastructure.web;

import lms.identity.auth.application.AuthDtos.LoginRequest;
import lms.identity.auth.application.AuthDtos.RefreshRequest;
import lms.identity.auth.application.AuthDtos.RegisterRequest;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.LogoutUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginUseCase login;
    private final RefreshTokenUseCase refresh;
    private final LogoutUseCase logout;
    private final RegisterCredentialUseCase register;

    public AuthController(LoginUseCase login, RefreshTokenUseCase refresh, LogoutUseCase logout,
                          RegisterCredentialUseCase register) {
        this.login = login;
        this.refresh = refresh;
        this.logout = logout;
        this.register = register;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return login.execute(request.username(), request.password());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return refresh.execute(request.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        logout.execute(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** Cần permission USER_CREATE. userId do user-service cấp; auth-service chỉ giữ credential. */
    @PostMapping("/credentials")
    public ResponseEntity<Map<String, UUID>> register(@Valid @RequestBody RegisterRequest request) {
        UUID userId = register.execute(request.userId(), request.username(), request.password(), request.departmentId());
        return ResponseEntity.created(URI.create("/api/auth/credentials/" + userId)).body(Map.of("userId", userId));
    }
}
