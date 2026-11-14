package lms.identity.auth.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialJpaRepository extends JpaRepository<UserCredentialJpaEntity, UUID> {

    Optional<UserCredentialJpaEntity> findByUsername(String username);

    boolean existsByUsername(String username);
}
