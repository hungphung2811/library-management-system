package lms.identity.contract.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.identity.contract.domain.BillingCycle;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractModule;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.contract.domain.ContractStatus;
import org.springframework.stereotype.Repository;

/** Adapter JPA cho tenant_contracts (+ tenant_contract_modules), bảng toàn cục của identity_db. */
@Repository
class ContractPersistenceAdapter implements ContractRepository {

    private final SpringDataContractRepository contracts;

    ContractPersistenceAdapter(SpringDataContractRepository contracts) {
        this.contracts = contracts;
    }

    @Override
    public Contract save(Contract c) {
        boolean exists = contracts.existsById(c.id());
        long version = exists ? c.version() + 1 : 0;
        ContractJpaEntity saved = contracts.saveAndFlush(new ContractJpaEntity(c.id(), c.tenantId(), c.name(),
                c.status().name(), c.legacy(), c.startsAt(), c.endsAt(), c.totalAmount(),
                c.billingCycle() == null ? null : c.billingCycle().unit().name(),
                c.billingCycle() == null ? null : c.billingCycle().value(), c.internalNote(), c.modulesAppliedAt(),
                c.retainedModule() == null ? null : c.retainedModule().name(), c.cancelReason(), c.createdAt(),
                c.createdBy(), c.updatedAt(), c.updatedBy(), version,
                c.modules().stream().map(Enum::name).collect(Collectors.toSet())));
        return toDomain(saved);
    }

    @Override
    public Optional<Contract> findById(UUID id) {
        return contracts.findById(id).map(ContractPersistenceAdapter::toDomain);
    }

    @Override
    public List<Contract> findByTenant(UUID tenantId) {
        return contracts.findByTenantIdOrderByStartsAtDesc(tenantId).stream()
                .map(ContractPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Contract> findActiveByTenant(UUID tenantId) {
        return contracts.findByTenantIdAndStatus(tenantId, ContractStatus.ACTIVE.name()).stream()
                .map(ContractPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Contract> findWithRetainedModule(UUID tenantId) {
        return contracts.findWithRetained(tenantId).stream().map(ContractPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Contract> findDueToApply(Instant now) {
        return contracts.findDueToApply(now).stream().map(ContractPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Contract> findDueToExpire(Instant threshold) {
        return contracts.findDueToExpire(threshold).stream().map(ContractPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Contract> findReminderCandidates(Instant now) {
        return contracts.findReminderCandidates(now).stream().map(ContractPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Contract> findExpiring(Instant horizon, Instant graceFloor) {
        return contracts.findExpiring(horizon, graceFloor).stream().map(ContractPersistenceAdapter::toDomain)
                .toList();
    }

    private static Contract toDomain(ContractJpaEntity e) {
        Set<ContractModule> modules = e.getModules().stream().map(ContractModule::parse)
                .collect(Collectors.toSet());
        return Contract.restore(e.getId(), e.getTenantId(), e.getName(), ContractStatus.valueOf(e.getStatus()),
                e.isLegacy(), e.getStartsAt(), e.getEndsAt(), e.getTotalAmount(),
                e.getBillingCycleUnit() == null ? null
                        : BillingCycle.ofNullable(e.getBillingCycleUnit(), e.getBillingCycleValue()),
                e.getInternalNote(), modules, e.getModulesAppliedAt(),
                e.getRetainedModule() == null ? null : ContractModule.parse(e.getRetainedModule()),
                e.getCancelReason(), e.getCreatedAt(), e.getCreatedBy(), e.getUpdatedAt(), e.getUpdatedBy(),
                e.getVersion());
    }
}
