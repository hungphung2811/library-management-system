package lms.identity.auth.domain;

import lms.identity.auth.domain.UserCredential;
import java.util.Optional;
import java.util.UUID;

public interface UserCredentialRepository {
    Optional<UserCredential> findByUsername(String username);

    Optional<UserCredential> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    boolean existsByUsername(String username);
}
