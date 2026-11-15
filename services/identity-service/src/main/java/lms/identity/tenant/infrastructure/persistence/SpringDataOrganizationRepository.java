package lms.identity.tenant.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOrganizationRepository extends JpaRepository<OrganizationJpaEntity, UUID> {
}
