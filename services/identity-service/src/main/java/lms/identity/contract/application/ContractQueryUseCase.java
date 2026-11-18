package lms.identity.contract.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.contract.application.ContractResponses.ContractDetailResponse;
import lms.identity.contract.application.ContractResponses.ContractResponse;
import lms.identity.contract.application.ContractResponses.ExpiringContractResponse;
import lms.identity.contract.application.ContractResponses.HistoryEntryResponse;
import lms.identity.contract.application.ContractResponses.PaymentResponse;
import lms.identity.contract.application.ContractResponses.TenantContractsResponse;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractException;
import lms.identity.contract.domain.ContractException.Reason;
import lms.identity.contract.domain.ContractHistoryRepository;
import lms.identity.contract.domain.ContractModule;
import lms.identity.contract.domain.ContractPayment;
import lms.identity.contract.domain.ContractPaymentRepository;
import lms.identity.contract.domain.ContractPeriod;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.tenant.application.TenantModuleOperations;
import lms.identity.tenant.application.TenantModuleOperations.TenantBrief;

/** Truy vấn chỉ đọc cho platform admin (không khóa, không cần TenantContext: bảng toàn cục). */
public class ContractQueryUseCase {

    public static final int DEFAULT_EXPIRING_DAYS = 30;
    public static final int MAX_EXPIRING_DAYS = 365;

    private final ContractRepository contracts;
    private final ContractPaymentRepository payments;
    private final ContractHistoryRepository history;
    private final TenantModuleOperations ops;
    private final ContractPolicy policy;
    private final Clock clock;

    public ContractQueryUseCase(ContractRepository contracts, ContractPaymentRepository payments,
                                ContractHistoryRepository history, TenantModuleOperations ops, ContractPolicy policy,
                                Clock clock) {
        this.contracts = contracts;
        this.payments = payments;
        this.history = history;
        this.ops = ops;
        this.policy = policy;
        this.clock = clock;
    }

    public TenantContractsResponse listByTenant(UUID tenantId) {
        ops.brief(tenantId).orElseThrow(
                () -> new ContractException(Reason.TENANT_NOT_FOUND, "Không tìm thấy tenant " + tenantId));
        Instant now = clock.instant();
        List<Contract> all = contracts.findByTenant(tenantId);
        Map<UUID, Long> paid = payments.paidByContracts(all.stream().map(Contract::id).toList());
        List<ContractResponse> rows = all.stream()
                .map(c -> ContractResponses.platform(c, paid.getOrDefault(c.id(), 0L), policy, now)).toList();
        List<String> enabled = ops.enabledModules(tenantId);
        List<ContractModule> applying = all.stream()
                .filter(c -> c.modulesApplied() && c.covers(now, policy.gracePeriod()))
                .flatMap(c -> c.modules().stream()).toList();
        List<String> outside = enabled.stream().filter(m -> !applying.contains(ContractModule.parse(m))).toList();
        return new TenantContractsResponse(rows, enabled, outside);
    }

    public ContractDetailResponse get(UUID contractId) {
        Instant now = clock.instant();
        Contract c = contracts.findById(contractId).orElseThrow(() -> ManageContractUseCase.notFound(contractId));
        List<ContractPayment> rows = payments.findByContract(contractId);
        Map<UUID, UUID> reversedBy = new HashMap<>();
        rows.stream().filter(ContractPayment::isReversal).forEach(p -> reversedBy.put(p.reversesPaymentId(), p.id()));
        long paid = rows.stream().mapToLong(ContractPayment::amount).sum();
        List<PaymentResponse> paymentRows = rows.stream().map(p -> PaymentResponse.of(p, reversedBy.get(p.id())))
                .toList();
        return ContractResponses.ContractDetailResponse.of(ContractResponses.platform(c, paid, policy, now),
                paymentRows);
    }

    /** Mới nhất trước. */
    public List<HistoryEntryResponse> history(UUID contractId) {
        contracts.findById(contractId).orElseThrow(() -> ManageContractUseCase.notFound(contractId));
        return history.findByContract(contractId).stream().map(HistoryEntryResponse::of).toList();
    }

    /** Hợp đồng ACTIVE không legacy có ends_at trong {@code withinDays} ngày tới hoặc đang ân hạn (sớm nhất trước). */
    public List<ExpiringContractResponse> expiring(Integer withinDays) {
        int days = withinDays == null ? DEFAULT_EXPIRING_DAYS : withinDays;
        if (days < 1 || days > MAX_EXPIRING_DAYS) {
            throw new ContractException(Reason.INVALID_CONTRACT,
                    "withinDays phải từ 1 đến " + MAX_EXPIRING_DAYS);
        }
        Instant now = clock.instant();
        Duration grace = policy.gracePeriod();
        List<Contract> rows = contracts.findExpiring(now.plus(days, ChronoUnit.DAYS), now.minus(grace));
        Map<UUID, Long> paid = payments.paidByContracts(rows.stream().map(Contract::id).toList());
        LocalDate today = now.atZone(policy.zone()).toLocalDate();
        // Một truy vấn cho mọi tenant trong danh sách (không tra từng tenant một)
        Map<UUID, TenantBrief> briefs = ops.briefs(rows.stream().map(Contract::tenantId).distinct().toList());
        List<ExpiringContractResponse> result = new ArrayList<>();
        for (Contract c : rows) {
            TenantBrief tenant = briefs.get(c.tenantId());
            LocalDate endDate = ContractPeriod.endDate(c.endsAt(), policy.zone());
            boolean inGrace = !now.isBefore(c.endsAt());
            result.add(new ExpiringContractResponse(c.id(), c.tenantId(), tenant == null ? null : tenant.code(),
                    tenant == null ? null : tenant.name(), c.name(), endDate,
                    ChronoUnit.DAYS.between(today, endDate), inGrace,
                    ContractPeriod.endDate(c.graceEndsAt(grace), policy.zone()),
                    c.totalAmount() - paid.getOrDefault(c.id(), 0L)));
        }
        return result;
    }
}
