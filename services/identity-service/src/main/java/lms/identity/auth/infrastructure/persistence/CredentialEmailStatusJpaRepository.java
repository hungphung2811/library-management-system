package lms.identity.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CredentialEmailStatusJpaRepository extends JpaRepository<CredentialEmailStatusJpaEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CredentialEmailStatus c set c.emailVerifiedAt = null where c.userId = :userId")
    int markUnverified(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CredentialEmailStatus c set c.emailVerifiedAt = :now where c.userId = :userId and c.emailVerifiedAt is null")
    int markVerified(@Param("userId") UUID userId, @Param("now") Instant now);
}
