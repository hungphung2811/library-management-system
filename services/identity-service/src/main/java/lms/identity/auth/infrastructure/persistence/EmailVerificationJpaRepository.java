package lms.identity.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationJpaRepository extends JpaRepository<EmailVerificationJpaEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EmailVerificationJpaEntity v set v.attempts = v.attempts + 1 where v.userId = :userId"
            + " and v.consumedAt is null and v.expiresAt > :now and v.attempts < :max")
    int reserveAttempt(@Param("userId") UUID userId, @Param("now") Instant now, @Param("max") int max);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EmailVerificationJpaEntity v set v.consumedAt = :now where v.userId = :userId and v.consumedAt is null")
    int consume(@Param("userId") UUID userId, @Param("now") Instant now);
}
