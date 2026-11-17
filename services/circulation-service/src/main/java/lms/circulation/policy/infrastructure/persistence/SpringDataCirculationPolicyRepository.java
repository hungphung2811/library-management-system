package lms.circulation.policy.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Hibernate tự lọc theo tenant (@TenantId) nên findAll() chỉ có tối đa một dòng của tenant hiện tại. */
interface SpringDataCirculationPolicyRepository extends JpaRepository<CirculationPolicyJpaEntity, UUID> {
}
