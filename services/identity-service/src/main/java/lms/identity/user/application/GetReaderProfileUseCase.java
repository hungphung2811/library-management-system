package lms.identity.user.application;

import lms.identity.user.domain.ReaderProfileNotFoundException;
import lms.identity.user.domain.ReaderProfileRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

public class GetReaderProfileUseCase {

    private final ReaderProfileRepository repository;
    private final Clock clock;

    public GetReaderProfileUseCase(ReaderProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public ReaderProfileResponse execute(UUID userId) {
        return repository.findByUserId(userId)
                .map(p -> ReaderProfileResponse.from(p, LocalDate.now(clock)))
                .orElseThrow(() -> new ReaderProfileNotFoundException(userId));
    }
}
