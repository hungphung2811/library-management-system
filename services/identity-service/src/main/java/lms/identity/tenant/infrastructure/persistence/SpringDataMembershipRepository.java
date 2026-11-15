package lms.identity.tenant.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMembershipRepository extends JpaRepository<MembershipJpaEntity, MembershipId> {

    List<MembershipJpaEntity> findByUserIdAndStatus(UUID userId, MembershipStatus status);
}
