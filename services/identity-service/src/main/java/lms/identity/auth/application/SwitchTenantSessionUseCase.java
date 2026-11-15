package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * POST /internal/auth/switch-tenant: người đang đăng nhập đổi sang tenant khác. Context tenant kiểm tra membership
 * ACTIVE + tenant ACTIVE và ghi tenant dùng gần nhất; ở đây cấp cặp access + refresh token mới cho tenant đó. Các
 * refresh token khác của người dùng không bị thu hồi (request chỉ mang access token).
 */
public class SwitchTenantSessionUseCase {

    private final UserCredentialRepository credentials;
    private final TenantAccessPort tenantAccess;
    private final TokenService tokenService;
    private final Clock clock;

    public SwitchTenantSessionUseCase(UserCredentialRepository credentials, TenantAccessPort tenantAccess,
                                      TokenService tokenService, Clock clock) {
        this.credentials = credentials;
        this.tenantAccess = tenantAccess;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    @Transactional
    public TokenResponse execute(UUID userId, UUID tenantId) {
        UserCredential credential = credentials.findByUserId(userId).orElseThrow(InvalidCredentialsException::new);
        credential.ensureCanLogin(clock.instant());
        return tokenService.issueForTenant(credential, tenantAccess.switchTo(userId, tenantId));
    }
}
