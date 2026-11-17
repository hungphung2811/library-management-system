package lms.identity.auth.application;

import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mở khóa tài khoản bị khóa do đăng nhập sai nhiều lần (xóa failedAttempts và lockedUntil). Phát AccountUnlockedEvent
 * (audit) dưới tenant của người thao tác. Quy tắc ai được mở khóa cho ai do context user kiểm trước khi gọi.
 */
public class UnlockCredentialUseCase {

    private final UserCredentialRepository repository;
    private final SaveCredentialPort savePort;
    private final PublishAuthEventPort eventPort;
    private final Clock clock;

    public UnlockCredentialUseCase(UserCredentialRepository repository, SaveCredentialPort savePort,
                                   PublishAuthEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.savePort = savePort;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /** @return false nếu người dùng chưa có credential */
    @Transactional
    public boolean execute(UUID userId, UUID actorId) {
        Optional<UserCredential> found = repository.findByUserId(userId);
        if (found.isEmpty()) return false;
        UserCredential credential = found.get();
        credential.unlock(actorId, clock.instant());
        savePort.save(credential);
        credential.pullEvents().forEach(eventPort::publish);
        return true;
    }
}
