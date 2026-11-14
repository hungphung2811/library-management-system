package lms.identity.auth.application;

import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.ExternalAuthException.Reason;
import lms.identity.auth.domain.ExternalLogin;
import lms.identity.auth.domain.ExternalLoginRepository;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đăng nhập bằng danh tính ngoài. Đã liên kết (provider, subject) thì đăng nhập đúng người đó, kể cả khi email ở IdP
 * đổi. Chưa liên kết thì chỉ tự liên kết khi email đã xác minh, tên miền được phép và có đúng một độc giả cùng email;
 * không bao giờ tự tạo tài khoản (độc giả do thủ thư cấp, UC10). Tài khoản bị khóa/vô hiệu hóa bị chặn trước khi tạo
 * liên kết. Không đụng bộ đếm sai mật khẩu. Trả mã dùng một lần để đổi lấy token.
 */
public class ExternalSignInUseCase {

    private final ExternalLoginRepository logins;
    private final UserCredentialRepository credentials;
    private final ReaderLookupPort readers;
    private final ExternalProviderPolicyPort policy;
    private final ExternalTicketPort tickets;
    private final Clock clock;

    public ExternalSignInUseCase(ExternalLoginRepository logins, UserCredentialRepository credentials,
                                 ReaderLookupPort readers, ExternalProviderPolicyPort policy,
                                 ExternalTicketPort tickets, Clock clock) {
        this.logins = logins;
        this.credentials = credentials;
        this.readers = readers;
        this.policy = policy;
        this.tickets = tickets;
        this.clock = clock;
    }

    private static ExternalAuthException notLinked() {
        return new ExternalAuthException(Reason.ACCOUNT_NOT_LINKED,
                "Tài khoản ngoài chưa được liên kết với tài khoản nào trong hệ thống");
    }

    @Transactional
    public String execute(ExternalIdentity identity) {
        Instant now = clock.instant();

        Optional<ExternalLogin> existing = logins.findByProviderAndSubject(identity.provider(), identity.subject());
        if (existing.isPresent()) {
            ExternalLogin login = existing.get();
            UserCredential credential = credentials.findByUserId(login.userId()).orElseThrow(ExternalSignInUseCase::notLinked);
            credential.ensureCanLogin(now);
            login.markUsed(now, identity.email());
            logins.save(login);
            return tickets.issue(credential.userId(), now);
        }

        String email = identity.email();
        if (!identity.emailVerified() || email == null || email.isBlank()
                || !policy.isEmailAllowed(identity.provider(), email)) {
            throw notLinked();
        }
        List<UUID> matches = readers.findUserIdsByEmail(email.trim());
        if (matches.size() != 1) {
            throw notLinked();
        }
        UUID userId = matches.get(0);
        UserCredential credential = credentials.findByUserId(userId).orElseThrow(ExternalSignInUseCase::notLinked);
        credential.ensureCanLogin(now);
        if (logins.findByUserIdAndProvider(userId, identity.provider()).isPresent()) {
            throw notLinked();
        }
        logins.save(ExternalLogin.link(userId, identity.provider(), identity.subject(), email.trim(), now));
        return tickets.issue(userId, now);
    }
}
