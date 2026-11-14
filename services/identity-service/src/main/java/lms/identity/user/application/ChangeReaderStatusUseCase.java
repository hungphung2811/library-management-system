package lms.identity.user.application;

import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderProfileNotFoundException;
import lms.identity.user.domain.ReaderProfileRepository;
import lms.identity.user.domain.ReaderStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** UC10: khóa/mở khóa tài khoản độc giả. */
public class ChangeReaderStatusUseCase {

    private final ReaderProfileRepository repository;
    private final Clock clock;

    public ChangeReaderStatusUseCase(ReaderProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ReaderProfileResponse execute(UUID userId, ReaderStatus target) {
        ReaderProfile profile = repository.findByUserId(userId)
                .orElseThrow(() -> new ReaderProfileNotFoundException(userId));
        if (target == ReaderStatus.LOCKED) {
            profile.lock();
        } else {
            profile.unlock();
        }
        return ReaderProfileResponse.from(repository.save(profile), LocalDate.now(clock));
    }
}
