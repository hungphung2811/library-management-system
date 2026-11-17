package lms.identity.contract.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.contract.domain.BillingCycle;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractHistoryEntry;
import lms.identity.contract.domain.ContractModule;
import lms.identity.contract.domain.ContractPayment;
import lms.identity.contract.domain.ContractPeriod;
import lms.identity.contract.domain.ContractPhase;

/** DTO trả về cho platform admin (đầy đủ) và cho tenant admin ({@link TenantContractView}: bản rút gọn, riêng biệt). */
public final class ContractResponses {

    private ContractResponses() {
    }

    public record BillingCycleView(String unit, int value) {
        static BillingCycleView of(BillingCycle c) {
            return c == null ? null : new BillingCycleView(c.unit().name(), c.value());
        }
    }

    public record ContractResponse(UUID id, UUID tenantId, String name, String status, String phase, boolean inGrace,
                                   boolean legacy, List<String> modules, LocalDate startDate, LocalDate endDate,
                                   LocalDate graceEndsDate, long totalAmount, long paidAmount, long outstandingAmount,
                                   BillingCycleView billingCycle, String internalNote, Instant modulesAppliedAt,
                                   String retainedModule, String cancelReason, Instant createdAt, UUID createdBy,
                                   Instant updatedAt, UUID updatedBy, long version) {
    }

    /** {@link ContractResponse} kèm các dòng thanh toán. */
    public record ContractDetailResponse(UUID id, UUID tenantId, String name, String status, String phase,
                                         boolean inGrace, boolean legacy, List<String> modules, LocalDate startDate,
                                         LocalDate endDate, LocalDate graceEndsDate, long totalAmount,
                                         long paidAmount, long outstandingAmount, BillingCycleView billingCycle,
                                         String internalNote, Instant modulesAppliedAt, String retainedModule,
                                         String cancelReason, Instant createdAt, UUID createdBy, Instant updatedAt,
                                         UUID updatedBy, long version, List<PaymentResponse> payments) {
        static ContractDetailResponse of(ContractResponse r, List<PaymentResponse> payments) {
            return new ContractDetailResponse(r.id(), r.tenantId(), r.name(), r.status(), r.phase(), r.inGrace(),
                    r.legacy(), r.modules(), r.startDate(), r.endDate(), r.graceEndsDate(), r.totalAmount(),
                    r.paidAmount(), r.outstandingAmount(), r.billingCycle(), r.internalNote(), r.modulesAppliedAt(),
                    r.retainedModule(), r.cancelReason(), r.createdAt(), r.createdBy(), r.updatedAt(), r.updatedBy(),
                    r.version(), payments);
        }
    }

    public record PaymentResponse(UUID id, LocalDate paidOn, long amount, String reference, String note,
                                  UUID reversesPaymentId, UUID reversedByPaymentId, UUID recordedBy,
                                  Instant recordedAt) {
        static PaymentResponse of(ContractPayment p, UUID reversedBy) {
            return new PaymentResponse(p.id(), p.paidOn(), p.amount(), p.reference(), p.note(),
                    p.reversesPaymentId(), reversedBy, p.recordedBy(), p.recordedAt());
        }
    }

    public record HistoryEntryResponse(UUID id, String type, UUID contractId, String actorType, UUID actorId,
                                       Instant occurredAt, Map<String, Object> data) {
        static HistoryEntryResponse of(ContractHistoryEntry e) {
            return new HistoryEntryResponse(e.id(), e.type(), e.contractId(), e.actorType().name(), e.actorId(),
                    e.occurredAt(), e.data());
        }
    }

    /** GET /api/platform/tenants/{id}/contracts. */
    public record TenantContractsResponse(List<ContractResponse> contracts, List<String> enabledModules,
                                          List<String> modulesOutsideContract) {
    }

    public record ExpiringContractResponse(UUID contractId, UUID tenantId, String tenantCode, String tenantName,
                                           String name, LocalDate endDate, long daysLeft, boolean inGrace,
                                           LocalDate graceEndsDate, long outstandingAmount) {
    }

    /**
     * Hợp đồng cho tenant admin (chỉ đọc). KHÔNG có internalNote, lịch sử, dòng thanh toán, retainedModule,
     * modulesOutsideContract, cancelReason, legacy; tiền chỉ là TỔNG (không giá từng module).
     */
    public record TenantContractView(UUID id, String name, String status, String phase, boolean inGrace,
                                     LocalDate graceEndsDate, List<String> modules, LocalDate startDate,
                                     LocalDate endDate, long totalAmount, long paidAmount, long outstandingAmount,
                                     BillingCycleView billingCycle) {
    }

    // ------------------------------------------------------------------ ánh xạ

    static ContractResponse platform(Contract c, long paid, ContractPolicy policy, Instant now) {
        ContractPhase phase = c.phase(now, policy.gracePeriod());
        return new ContractResponse(c.id(), c.tenantId(), c.name(), c.status().name(),
                phase == null ? null : phase.name(), phase == ContractPhase.IN_GRACE, c.legacy(), names(c),
                ContractPeriod.startDate(c.startsAt(), policy.zone()), endDate(c, policy), graceEndDate(c, policy),
                c.totalAmount(), paid, c.totalAmount() - paid, BillingCycleView.of(c.billingCycle()),
                c.internalNote(), c.modulesAppliedAt(), c.retainedModule() == null ? null : c.retainedModule().name(),
                c.cancelReason(), c.createdAt(), c.createdBy(), c.updatedAt(), c.updatedBy(), c.version());
    }

    static TenantContractView tenantView(Contract c, long paid, ContractPolicy policy, Instant now) {
        ContractPhase phase = c.phase(now, policy.gracePeriod());
        return new TenantContractView(c.id(), c.name(), c.status().name(), phase == null ? null : phase.name(),
                phase == ContractPhase.IN_GRACE, graceEndDate(c, policy), names(c),
                ContractPeriod.startDate(c.startsAt(), policy.zone()), endDate(c, policy), c.totalAmount(), paid,
                c.totalAmount() - paid, BillingCycleView.of(c.billingCycle()));
    }

    private static List<String> names(Contract c) {
        return c.modules().stream().sorted().map(ContractModule::name).toList();
    }

    private static LocalDate endDate(Contract c, ContractPolicy policy) {
        return ContractPeriod.endDate(c.endsAt(), policy.zone());
    }

    private static LocalDate graceEndDate(Contract c, ContractPolicy policy) {
        Instant graceEnds = c.graceEndsAt(policy.gracePeriod());
        return graceEnds == null ? null : ContractPeriod.endDate(graceEnds, policy.zone());
    }
}
