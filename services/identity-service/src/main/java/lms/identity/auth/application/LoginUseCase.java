package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.PasswordHasherPort;
import lms.identity.auth.application.PublishAuthEventPort;
import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

public class LoginUseCase {

    private final UserCredentialRepository repository;
    private final SaveCredentialPort savePort;
    private final PasswordHasherPort hasher;
    private final TokenService tokenService;
    private final PublishAuthEventPort eventPort;
    private final Clock clock;

    public LoginUseCase(UserCredentialRepository repository, SaveCredentialPort savePort, PasswordHasherPort hasher,
                        TokenService tokenService, PublishAuthEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.savePort = savePort;
        this.hasher = hasher;
        this.tokenService = tokenService;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /**
     * noRollbackFor: đăng nhập sai vẫn phải lưu số lần thất bại (để khóa tài khoản) dù ném exception.
     */
    public TokenResponse execute(String username, String rawPassword) {
        return execute(username, rawPassword, null);
    }

    /**
     * tenantCodeOrNull: mã tenant muốn vào (tùy chọn). Không truy cập được tenant (hoặc không còn tenant nào) thì ném
     * NoTenantAccessException của context tenant sau khi đã xác thực mật khẩu; giao dịch rollback nên không ghi nhận
     * lần đăng nhập thành công.
     */
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public TokenResponse execute(String username, String rawPassword, String tenantCodeOrNull) {
        Instant now = clock.instant();
        String normalized = username == null ? "" : username.trim().toLowerCase();
        UserCredential credential = repository.findByUsername(normalized).orElseThrow(InvalidCredentialsException::new);

        credential.ensureCanLogin(now);

        if (!hasher.matches(rawPassword, credential.passwordHash())) {
            credential.recordFailure(now);
            savePort.save(credential);
            credential.pullEvents().forEach(eventPort::publish);
            throw new InvalidCredentialsException();
        }

        credential.recordSuccess(now);
        savePort.save(credential);
        TokenResponse response = tokenService.issueFor(credential, tenantCodeOrNull);
        credential.pullEvents().forEach(eventPort::publish);
        return response;
    }
}
