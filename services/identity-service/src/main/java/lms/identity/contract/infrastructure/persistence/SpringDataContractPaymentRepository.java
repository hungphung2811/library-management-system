package lms.identity.contract.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataContractPaymentRepository extends JpaRepository<ContractPaymentJpaEntity, UUID> {

    List<ContractPaymentJpaEntity> findByContractIdOrderByRecordedAtAscIdAsc(UUID contractId);

    boolean existsByReversesPaymentId(UUID reversesPaymentId);

    @Query("select p.contractId, sum(p.amount) from ContractPaymentJpaEntity p "
            + "where p.contractId in :ids group by p.contractId")
    List<Object[]> sumByContracts(@Param("ids") Collection<UUID> ids);
}
