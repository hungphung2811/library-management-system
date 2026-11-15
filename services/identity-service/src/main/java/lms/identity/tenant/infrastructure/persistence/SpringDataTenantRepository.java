package lms.identity.tenant.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTenantRepository extends JpaRepository<TenantJpaEntity, UUID>,
        JpaSpecificationExecutor<TenantJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TenantJpaEntity t where t.id = :id")
    Optional<TenantJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<TenantJpaEntity> findByCode(String code);

    List<TenantJpaEntity> findAllByOrderByCode();
}
