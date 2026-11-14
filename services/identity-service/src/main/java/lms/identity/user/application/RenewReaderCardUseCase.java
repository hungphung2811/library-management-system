package lms.identity.user.application;

import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderProfileNotFoundException;
import lms.identity.user.domain.ReaderProfileRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** UC10: gia hạn thẻ. */
public class RenewReaderCardUseCase {

    private final ReaderProfileRepository repository;
    private final Clock clock;

    public RenewReaderCardUseCase(ReaderProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ReaderProfileResponse execute(UUID userId, LocalDate newExpiryDate) {
        LocalDate today = LocalDate.now(clock);
        ReaderProfile profile = repository.findByUserId(userId)
                .orElseThrow(() -> new ReaderProfileNotFoundException(userId));
        profile.renewCard(newExpiryDate, today);
        return ReaderProfileResponse.from(repository.save(profile), today);
    }
}
