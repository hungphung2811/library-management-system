package lms.identity.auth.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialJpaRepository extends JpaRepository<UserCredentialJpaEntity, UUID> {

    Optional<UserCredentialJpaEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    @org.springframework.data.jpa.repository.Query("select c.username from UserCredentialJpaEntity c where c.username in :usernames")
    java.util.List<String> findUsernamesIn(@org.springframework.data.repository.query.Param("usernames") java.util.Collection<String> usernames);
}
