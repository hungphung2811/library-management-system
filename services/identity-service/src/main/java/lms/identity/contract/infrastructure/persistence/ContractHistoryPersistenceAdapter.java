package lms.identity.contract.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import lms.identity.contract.domain.ContractHistoryEntry;
import lms.identity.contract.domain.ContractHistoryRepository;
import org.springframework.stereotype.Repository;

/** Adapter chỉ thêm cho tenant_contract_history. */
@Repository
class ContractHistoryPersistenceAdapter implements ContractHistoryRepository {

    private final SpringDataContractHistoryRepository history;

    ContractHistoryPersistenceAdapter(SpringDataContractHistoryRepository history) {
        this.history = history;
    }

    @Override
    public void append(ContractHistoryEntry e) {
        history.saveAndFlush(new ContractHistoryJpaEntity(e.id(), e.tenantId(), e.contractId(), e.type(),
                e.actorId(), e.actorType().name(), e.occurredAt(), e.data()));
    }

    @Override
    public List<ContractHistoryEntry> findByContract(UUID contractId) {
        return history.findByContractIdOrderByOccurredAtDescIdDesc(contractId).stream()
                .map(e -> new ContractHistoryEntry(e.getId(), e.getTenantId(), e.getContractId(), e.getType(),
                        ContractHistoryEntry.ActorType.valueOf(e.getActorType()), e.getActorId(), e.getOccurredAt(),
                        e.getData()))
                .toList();
    }
}
