package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.ExternalAuthException.Reason;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Đổi mã dùng một lần lấy cặp token. Kiểm tra lại tài khoản còn dùng được ở thời điểm đổi. */
public class ExchangeExternalTicketUseCase {

    private final ExternalTicketPort tickets;
    private final UserCredentialRepository credentials;
    private final TokenService tokenService;
    private final Clock clock;

    public ExchangeExternalTicketUseCase(ExternalTicketPort tickets, UserCredentialRepository credentials,
                                         TokenService tokenService, Clock clock) {
        this.tickets = tickets;
        this.credentials = credentials;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    @Transactional
    public TokenResponse execute(String rawCode) {
        Instant now = clock.instant();
        UUID userId = tickets.redeem(rawCode, now).orElseThrow(() ->
                new ExternalAuthException(Reason.INVALID_TICKET, "Mã đăng nhập không hợp lệ hoặc đã hết hạn"));
        UserCredential credential = credentials.findByUserId(userId).orElseThrow(() ->
                new ExternalAuthException(Reason.INVALID_TICKET, "Mã đăng nhập không hợp lệ hoặc đã hết hạn"));
        credential.ensureCanLogin(now);
        return tokenService.issueForExternal(credential);
    }
}
