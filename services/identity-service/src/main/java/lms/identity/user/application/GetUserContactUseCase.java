package lms.identity.user.application;

import java.util.Optional;
import java.util.UUID;
import lms.identity.user.domain.UserRepository;

/** API của context user cho context auth (xác minh email): tra người dùng theo email và email theo người dùng. */
public class GetUserContactUseCase {

    private final UserRepository repository;

    public GetUserContactUseCase(UserRepository repository) {
        this.repository = repository;
    }

    public Optional<UUID> userIdByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return repository.findByEmail(email.trim()).map(u -> u.id());
    }

    public Optional<String> emailOf(UUID userId) {
        return repository.findById(userId).map(u -> u.email());
    }
}
