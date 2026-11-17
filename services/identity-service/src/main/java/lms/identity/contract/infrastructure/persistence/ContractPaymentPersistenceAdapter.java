package lms.identity.contract.infrastructure.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.identity.contract.domain.ContractPayment;
import lms.identity.contract.domain.ContractPaymentRepository;
import org.springframework.stereotype.Repository;

/** Adapter chỉ thêm cho tenant_contract_payments: không có đường update/delete. */
@Repository
class ContractPaymentPersistenceAdapter implements ContractPaymentRepository {

    private final SpringDataContractPaymentRepository payments;

    ContractPaymentPersistenceAdapter(SpringDataContractPaymentRepository payments) {
        this.payments = payments;
    }

    @Override
    public ContractPayment append(ContractPayment p) {
        payments.saveAndFlush(new ContractPaymentJpaEntity(p.id(), p.contractId(), p.tenantId(), p.paidOn(),
                p.amount(), p.note(), p.reference(), p.reversesPaymentId(), p.recordedBy(), p.recordedAt()));
        return p;
    }

    @Override
    public Optional<ContractPayment> findById(UUID id) {
        return payments.findById(id).map(ContractPaymentPersistenceAdapter::toDomain);
    }

    @Override
    public List<ContractPayment> findByContract(UUID contractId) {
        return payments.findByContractIdOrderByRecordedAtAscIdAsc(contractId).stream()
                .map(ContractPaymentPersistenceAdapter::toDomain).toList();
    }

    @Override
    public Map<UUID, Long> paidByContracts(Collection<UUID> contractIds) {
        Map<UUID, Long> result = new HashMap<>();
        if (contractIds.isEmpty()) return result;
        for (Object[] row : payments.sumByContracts(contractIds)) {
            result.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    @Override
    public boolean isReversed(UUID paymentId) {
        return payments.existsByReversesPaymentId(paymentId);
    }

    private static ContractPayment toDomain(ContractPaymentJpaEntity e) {
        return new ContractPayment(e.getId(), e.getContractId(), e.getTenantId(), e.getPaidOn(), e.getAmount(),
                e.getReference(), e.getNote(), e.getReversesPaymentId(), e.getRecordedBy(), e.getRecordedAt());
    }
}
