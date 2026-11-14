package lms.identity.user.application;

import lms.identity.user.domain.ReaderProfileRepository;
import java.util.List;
import java.util.UUID;

/** API của context user cho context khác (auth): tra userId theo email hồ sơ độc giả. */
public class FindReaderUserIdsByEmailUseCase {

    private final ReaderProfileRepository repository;

    public FindReaderUserIdsByEmailUseCase(ReaderProfileRepository repository) {
        this.repository = repository;
    }

    public List<UUID> execute(String email) {
        return repository.findUserIdsByEmail(email);
    }
}
