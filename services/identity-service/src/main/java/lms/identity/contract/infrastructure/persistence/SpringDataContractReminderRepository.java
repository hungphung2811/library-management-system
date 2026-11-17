package lms.identity.contract.infrastructure.persistence;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataContractReminderRepository
        extends JpaRepository<ContractReminderJpaEntity, ContractReminderJpaEntity.Key> {

    @Query("select r.key.daysBefore from ContractReminderJpaEntity r "
            + "where r.key.contractId = :contractId and r.key.endsAt = :endsAt")
    Set<Integer> recordedDays(@Param("contractId") UUID contractId, @Param("endsAt") Instant endsAt);
}
