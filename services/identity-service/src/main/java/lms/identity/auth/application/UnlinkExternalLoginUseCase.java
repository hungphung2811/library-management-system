package lms.identity.auth.application;

import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.ExternalAuthException.Reason;
import lms.identity.auth.domain.ExternalLoginRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Gỡ liên kết. Mọi tài khoản đều còn mật khẩu nội bộ nên không lo mất phương thức đăng nhập cuối cùng. */
public class UnlinkExternalLoginUseCase {

    private final ExternalLoginRepository logins;

    public UnlinkExternalLoginUseCase(ExternalLoginRepository logins) {
        this.logins = logins;
    }

    @Transactional
    public void execute(UUID userId, String provider) {
        var login = logins.findByUserIdAndProvider(userId, provider).orElseThrow(() ->
                new ExternalAuthException(Reason.EXTERNAL_LOGIN_NOT_FOUND, "Chưa liên kết với " + provider));
        logins.delete(login);
    }
}
