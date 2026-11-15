package lms.identity.tenant.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTenantRepository extends JpaRepository<TenantJpaEntity, UUID> {

    Optional<TenantJpaEntity> findByCode(String code);

    List<TenantJpaEntity> findAllByOrderByCode();
}
