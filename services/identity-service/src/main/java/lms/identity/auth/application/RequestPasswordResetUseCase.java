package lms.identity.auth.application;

import lms.identity.auth.domain.PasswordResetRequestedEvent;
import lms.identity.auth.domain.PasswordResetToken;
import lms.identity.auth.domain.PasswordResetTokenRepository;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.tenant.application.TransactionalRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quên mật khẩu, bước yêu cầu. Công khai nên KHÔNG BAO GIỜ tiết lộ tài khoản có tồn tại hay không: mọi nhánh (không có
 * tài khoản, tài khoản bị vô hiệu hóa, vượt giới hạn tần suất, thành công) trả về như nhau (void, controller luôn 202).
 *
 * <p>Giới hạn tần suất theo tài khoản: tối đa {@value #MAX_REQUESTS_PER_WINDOW} token trong {@link #RATE_WINDOW} (đếm
 * từ bảng token); vượt thì bỏ qua yên lặng. Theo IP do nginx giới hạn. Token mới vô hiệu mọi token cũ chưa dùng. Chỉ lưu
 * hash; token thô đi vào {@link PasswordResetRequestedEvent} SAU khi transaction ghi token đã commit.
 */
public class RequestPasswordResetUseCase {

    public static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    public static final int MAX_REQUESTS_PER_WINDOW = 3;
    public static final Duration RATE_WINDOW = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(RequestPasswordResetUseCase.class);

    private final UserCredentialRepository credentials;
    private final PasswordResetTokenRepository tokens;
    private final SavePasswordResetTokenPort savePort;
    private final RefreshTokenCodecPort codec;
    private final PublishPasswordResetPort publisher;
    private final UserDirectoryPort userDirectory;
    private final TransactionalRunner tx;
    private final Clock clock;

    public RequestPasswordResetUseCase(UserCredentialRepository credentials, PasswordResetTokenRepository tokens,
                                       SavePasswordResetTokenPort savePort, RefreshTokenCodecPort codec,
                                       PublishPasswordResetPort publisher, UserDirectoryPort userDirectory,
                                       TransactionalRunner tx, Clock clock) {
        this.credentials = credentials;
        this.tokens = tokens;
        this.savePort = savePort;
        this.codec = codec;
        this.publisher = publisher;
        this.userDirectory = userDirectory;
        this.tx = tx;
        this.clock = clock;
    }

    public void execute(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return;
        Optional<UserCredential> found = credentials.findByUsername(normalized);
        if (found.isEmpty()) return;
        UserCredential credential = found.get();
        boolean disabledProfile = userDirectory.find(credential.userId())
                .map(UserDirectoryPort.UserInfo::disabled).orElse(false);
        if (!credential.isActive() || disabledProfile) return;
        Instant now = clock.instant();
        UUID userId = credential.userId();
        if (tokens.countCreatedSince(userId, now.minus(RATE_WINDOW)) >= MAX_REQUESTS_PER_WINDOW) {
            log.info("Quên mật khẩu: vượt giới hạn tần suất, bỏ qua yêu cầu (userId={})", userId);
            return;
        }
        String raw = codec.generate();
        PasswordResetToken token = PasswordResetToken.issue(userId, codec.hash(raw), now, TOKEN_TTL);
        tx.inTransaction(() -> {
            savePort.invalidateOutstanding(userId, now);
            return savePort.save(token);
        });
        publisher.publish(new PasswordResetRequestedEvent(UUID.randomUUID(), userId, credential.username(), raw,
                token.expiresAt(), now));
    }
}
