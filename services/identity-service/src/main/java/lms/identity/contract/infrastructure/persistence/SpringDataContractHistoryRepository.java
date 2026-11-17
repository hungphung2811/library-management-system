package lms.identity.contract.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataContractHistoryRepository extends JpaRepository<ContractHistoryJpaEntity, UUID> {

    List<ContractHistoryJpaEntity> findByContractIdOrderByOccurredAtDescIdDesc(UUID contractId);
}
