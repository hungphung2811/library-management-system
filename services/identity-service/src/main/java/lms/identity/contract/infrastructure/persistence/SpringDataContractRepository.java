package lms.identity.contract.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataContractRepository extends JpaRepository<ContractJpaEntity, UUID> {

    List<ContractJpaEntity> findByTenantIdOrderByStartsAtDesc(UUID tenantId);

    List<ContractJpaEntity> findByTenantIdAndStatus(UUID tenantId, String status);

    @Query("select c from ContractJpaEntity c where c.tenantId = :tenantId and c.retainedModule is not null "
            + "and c.status in ('EXPIRED', 'CANCELLED')")
    List<ContractJpaEntity> findWithRetained(@Param("tenantId") UUID tenantId);

    @Query("select c from ContractJpaEntity c where c.status = 'ACTIVE' and c.modulesAppliedAt is null "
            + "and c.startsAt <= :now order by c.startsAt")
    List<ContractJpaEntity> findDueToApply(@Param("now") Instant now);

    @Query("select c from ContractJpaEntity c where c.status = 'ACTIVE' and c.endsAt is not null "
            + "and c.endsAt <= :threshold order by c.endsAt")
    List<ContractJpaEntity> findDueToExpire(@Param("threshold") Instant threshold);

    @Query("select c from ContractJpaEntity c where c.status = 'ACTIVE' and c.legacy = false "
            + "and c.endsAt is not null and c.startsAt <= :now and c.endsAt > :now order by c.endsAt")
    List<ContractJpaEntity> findReminderCandidates(@Param("now") Instant now);

    @Query("select c from ContractJpaEntity c where c.status = 'ACTIVE' and c.legacy = false "
            + "and c.endsAt is not null and c.endsAt <= :horizon and c.endsAt > :graceFloor order by c.endsAt")
    List<ContractJpaEntity> findExpiring(@Param("horizon") Instant horizon, @Param("graceFloor") Instant graceFloor);
}
