package lms.identity.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, UUID> {

    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

    // Mọi câu thu hồi ghi revoked_at/revoked_reason và xóa nhãn thiết bị + IP của dòng (không giữ lịch sử IP).
    String REVOKE_SET = "update RefreshTokenJpaEntity t set t.revoked = true, t.revokedAt = :now, "
            + "t.revokedReason = :reason, t.deviceSummary = null, t.clientIp = null ";

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(REVOKE_SET + "where t.userId = :userId and t.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now, @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(REVOKE_SET + "where t.userId = :userId and t.tenantId = :tenantId and t.revoked = false")
    int revokeAllByUserIdAndTenantId(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId,
                                     @Param("now") Instant now, @Param("reason") String reason);

    /** Xoay vòng có điều kiện (H3): chỉ thành công cho đúng một request; 0 dòng = đã bị xoay/thu hồi trước. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(REVOKE_SET + "where t.id = :id and t.revoked = false")
    int revokeIfActive(@Param("id") UUID id, @Param("now") Instant now, @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(REVOKE_SET + "where t.sessionId = :sessionId and t.revoked = false")
    int revokeBySession(@Param("sessionId") UUID sessionId, @Param("now") Instant now,
                        @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(REVOKE_SET + "where t.sessionId = :sessionId and t.userId = :userId and t.revoked = false")
    int revokeBySessionOfUser(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId,
                              @Param("now") Instant now, @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(REVOKE_SET + "where t.sessionId = :sessionId and t.userId = :userId and t.tenantId = :tenantId "
            + "and t.revoked = false")
    int revokeBySessionInTenant(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId,
                                @Param("sessionId") UUID sessionId, @Param("now") Instant now,
                                @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(REVOKE_SET + "where t.userId = :userId and t.sessionId <> :keep and t.revoked = false")
    int revokeAllExceptSession(@Param("userId") UUID userId, @Param("keep") UUID keepSessionId,
                               @Param("now") Instant now, @Param("reason") String reason);

    @Query("select t from RefreshTokenJpaEntity t where t.userId = :userId and t.revoked = false "
            + "and t.expiresAt > :now order by t.createdAt desc")
    List<RefreshTokenJpaEntity> findActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query("select t from RefreshTokenJpaEntity t where t.userId = :userId and t.tenantId = :tenantId "
            + "and t.revoked = false and t.expiresAt > :now order by t.createdAt desc")
    List<RefreshTokenJpaEntity> findActiveByUserIdAndTenantId(@Param("userId") UUID userId,
                                                              @Param("tenantId") UUID tenantId,
                                                              @Param("now") Instant now);

    @Query("select t.id from RefreshTokenJpaEntity t where t.expiresAt < :cutoff")
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshTokenJpaEntity t where t.id in :ids")
    int deleteByIds(@Param("ids") Collection<UUID> ids);
}
