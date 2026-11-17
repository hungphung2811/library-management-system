package lms.identity.user.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserMembershipRepository
        extends JpaRepository<UserMembershipJpaEntity, UserMembershipJpaEntity.Key>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<UserMembershipJpaEntity> {

    long countByUserId(UUID userId);

    List<UserMembershipJpaEntity> findByTenantIdAndUserIdIn(UUID tenantId, Collection<UUID> userIds);
}
