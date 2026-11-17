package lms.identity.contract.infrastructure.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Bảng toàn cục tenant_contracts (không RLS, không @TenantId: nằm trong TENANT_EXEMPT_TABLES, ADR-0012). Cột
 * {@code version} do adapter tự tăng (không dùng @Version: mọi thao tác ghi đã tuần tự hóa bằng khóa dòng tenant).
 */
@Entity
@Table(name = "tenant_contracts")
class ContractJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private boolean legacy;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "billing_cycle_unit")
    private String billingCycleUnit;

    @Column(name = "billing_cycle_value")
    private Integer billingCycleValue;

    @Column(name = "internal_note")
    private String internalNote;

    @Column(name = "modules_applied_at")
    private Instant modulesAppliedAt;

    @Column(name = "retained_module")
    private String retainedModule;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(nullable = false)
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tenant_contract_modules", joinColumns = @JoinColumn(name = "contract_id"))
    @Column(name = "module", nullable = false)
    private Set<String> modules = new HashSet<>();

    protected ContractJpaEntity() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    ContractJpaEntity(UUID id, UUID tenantId, String name, String status, boolean legacy, Instant startsAt,
                      Instant endsAt, long totalAmount, String billingCycleUnit, Integer billingCycleValue,
                      String internalNote, Instant modulesAppliedAt, String retainedModule, String cancelReason,
                      Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy, long version,
                      Set<String> modules) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.status = status;
        this.legacy = legacy;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.totalAmount = totalAmount;
        this.billingCycleUnit = billingCycleUnit;
        this.billingCycleValue = billingCycleValue;
        this.internalNote = internalNote;
        this.modulesAppliedAt = modulesAppliedAt;
        this.retainedModule = retainedModule;
        this.cancelReason = cancelReason;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
        this.version = version;
        this.modules = new HashSet<>(modules);
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getName() { return name; }
    String getStatus() { return status; }
    boolean isLegacy() { return legacy; }
    Instant getStartsAt() { return startsAt; }
    Instant getEndsAt() { return endsAt; }
    long getTotalAmount() { return totalAmount; }
    String getBillingCycleUnit() { return billingCycleUnit; }
    Integer getBillingCycleValue() { return billingCycleValue; }
    String getInternalNote() { return internalNote; }
    Instant getModulesAppliedAt() { return modulesAppliedAt; }
    String getRetainedModule() { return retainedModule; }
    String getCancelReason() { return cancelReason; }
    Instant getCreatedAt() { return createdAt; }
    UUID getCreatedBy() { return createdBy; }
    Instant getUpdatedAt() { return updatedAt; }
    UUID getUpdatedBy() { return updatedBy; }
    long getVersion() { return version; }
    Set<String> getModules() { return modules; }
}
