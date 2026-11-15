package lms.identity.tenant.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMembershipRepository extends JpaRepository<MembershipJpaEntity, MembershipId> {

    List<MembershipJpaEntity> findByUserIdAndStatus(UUID userId, MembershipStatus status);

    boolean existsByUserId(UUID userId);

    /** Mỗi dòng: [tenantId, số membership có trạng thái status]. */
    @Query("SELECT m.tenantId, COUNT(m) FROM MembershipJpaEntity m WHERE m.tenantId IN :tenantIds AND m.status = :status "
            + "GROUP BY m.tenantId")
    List<Object[]> countByTenant(@Param("tenantIds") Collection<UUID> tenantIds,
                                 @Param("status") MembershipStatus status);
}
