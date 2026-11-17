package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.ExternalAuthException.Reason;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Đổi mã dùng một lần lấy cặp token. Kiểm tra lại tài khoản còn dùng được ở thời điểm đổi.
 *
 * <p>Không có transaction bao ngoài (R20): mã được đánh dấu đã dùng trong transaction riêng của adapter (đã commit), rồi
 * mới cấp token. Cấp token thất bại (ví dụ không còn tenant nào) thì mã vẫn đã dùng, người dùng đăng nhập lại.
 */
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

    public TokenResponse execute(String rawCode) {
        return execute(rawCode, null);
    }

    public TokenResponse execute(String rawCode, ClientInfo client) {
        Instant now = clock.instant();
        UUID userId = tickets.redeem(rawCode, now).orElseThrow(() ->
                new ExternalAuthException(Reason.INVALID_TICKET, "Mã đăng nhập không hợp lệ hoặc đã hết hạn"));
        UserCredential credential = credentials.findByUserId(userId).orElseThrow(() ->
                new ExternalAuthException(Reason.INVALID_TICKET, "Mã đăng nhập không hợp lệ hoặc đã hết hạn"));
        credential.ensureCanLogin(now);
        return tokenService.issueForExternal(credential, client);
    }
}
