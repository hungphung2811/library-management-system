package lms.identity.tenant.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface SpringDataTenantRepository extends JpaRepository<TenantJpaEntity, UUID>,
        JpaSpecificationExecutor<TenantJpaEntity> {

    Optional<TenantJpaEntity> findByCode(String code);

    List<TenantJpaEntity> findAllByOrderByCode();
}
