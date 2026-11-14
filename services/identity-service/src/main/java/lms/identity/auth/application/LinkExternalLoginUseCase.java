package lms.identity.auth.application;

import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.ExternalAuthException.Reason;
import lms.identity.auth.domain.ExternalLogin;
import lms.identity.auth.domain.ExternalLoginRepository;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Người dùng đã đăng nhập chủ động liên kết thêm một tài khoản ngoài. */
public class LinkExternalLoginUseCase {

    private final ExternalLoginRepository logins;
    private final UserCredentialRepository credentials;
    private final Clock clock;

    public LinkExternalLoginUseCase(ExternalLoginRepository logins, UserCredentialRepository credentials, Clock clock) {
        this.logins = logins;
        this.credentials = credentials;
        this.clock = clock;
    }

    @Transactional
    public ExternalLoginResponse execute(UUID userId, ExternalIdentity identity) {
        if (!credentials.existsByUserId(userId)) {
            throw new ExternalAuthException(Reason.ACCOUNT_NOT_LINKED, "Không tìm thấy tài khoản để liên kết");
        }
        Optional<ExternalLogin> bySubject = logins.findByProviderAndSubject(identity.provider(), identity.subject());
        if (bySubject.isPresent()) {
            if (bySubject.get().userId().equals(userId)) {
                return ExternalLoginResponse.from(bySubject.get());
            }
            throw new ExternalAuthException(Reason.ALREADY_LINKED, "Tài khoản ngoài này đã liên kết với người dùng khác");
        }
        if (logins.findByUserIdAndProvider(userId, identity.provider()).isPresent()) {
            throw new ExternalAuthException(Reason.ALREADY_LINKED,
                    "Người dùng đã liên kết một tài khoản " + identity.provider() + " khác, hãy gỡ liên kết trước");
        }
        ExternalLogin saved = logins.save(ExternalLogin.link(userId, identity.provider(), identity.subject(),
                identity.email(), clock.instant()));
        return ExternalLoginResponse.from(saved);
    }
}
