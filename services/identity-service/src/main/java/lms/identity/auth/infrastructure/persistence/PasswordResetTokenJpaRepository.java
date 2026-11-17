package lms.identity.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenJpaRepository extends JpaRepository<PasswordResetTokenJpaEntity, UUID> {

    Optional<PasswordResetTokenJpaEntity> findByTokenHash(String tokenHash);

    long countByUserIdAndCreatedAtGreaterThanEqual(UUID userId, Instant since);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetTokenJpaEntity t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int invalidateOutstanding(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetTokenJpaEntity t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int consume(@Param("id") UUID id, @Param("now") Instant now);
}
