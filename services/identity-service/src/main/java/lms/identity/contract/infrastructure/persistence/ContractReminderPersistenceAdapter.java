package lms.identity.contract.infrastructure.persistence;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lms.identity.contract.domain.ContractReminderRepository;
import org.springframework.stereotype.Repository;

/** Adapter cho tenant_contract_reminders (PK chống gửi trùng). */
@Repository
class ContractReminderPersistenceAdapter implements ContractReminderRepository {

    private final SpringDataContractReminderRepository reminders;

    ContractReminderPersistenceAdapter(SpringDataContractReminderRepository reminders) {
        this.reminders = reminders;
    }

    @Override
    public Set<Integer> recordedDays(UUID contractId, Instant endsAt) {
        return reminders.recordedDays(contractId, endsAt);
    }

    @Override
    public boolean record(UUID contractId, int daysBefore, Instant endsAt, int recipientCount, boolean skipped,
                          Instant now) {
        ContractReminderJpaEntity.Key key = new ContractReminderJpaEntity.Key(contractId, daysBefore, endsAt);
        if (reminders.existsById(key)) return false;
        reminders.saveAndFlush(new ContractReminderJpaEntity(contractId, daysBefore, endsAt, now, recipientCount,
                skipped));
        return true;
    }
}
