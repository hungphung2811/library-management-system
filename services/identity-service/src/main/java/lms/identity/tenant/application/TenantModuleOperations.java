package lms.identity.tenant.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.SuspensionCause;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantModuleEntry;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantModules;
import lms.identity.tenant.domain.TenantRepository;

/**
 * Phần lõi bật/tắt module và đổi trạng thái tenant do hợp đồng (ADR-0012 mục 2.1). KHÔNG tự mở transaction, KHÔNG phát
 * event: người gọi đã đặt {@code TenantContext.callAs(tenantId)} và đang trong MỘT transaction đã khóa dòng tenant
 * ({@link #lockTenant}), rồi phát event sau commit từ danh sách thay đổi trả về. Dùng chung cho đường tay
 * ({@code Enable/DisableTenantModuleUseCase}) và context {@code contract} (kiểu trả về chỉ gồm kiểu của tầng
 * application để context khác không phải biết domain của tenant).
 */
public class TenantModuleOperations {

    /** Ảnh chụp trạng thái tenant sau khi khóa. {@code status}: ACTIVE | SUSPENDED; {@code suspensionCause} null khi ACTIVE. */
    public record TenantState(UUID id, String status, String suspensionCause, UUID suspendedByContractId) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        static TenantState of(Tenant t) {
            return new TenantState(t.id(), t.status().name(),
                    t.suspensionCause() == null ? null : t.suspensionCause().name(), t.suspendedByContractId());
        }
    }

    /** Một lần bật/tắt module có thật (rolesCreated: số role mẫu đã chép khi bật). */
    public record ModuleChange(String module, boolean enabled, Instant updatedAt, UUID updatedBy, int rolesCreated) {
    }

    /** Nguyên nhân tạm ngưng do hợp đồng. */
    public enum ContractSuspension {
        EXPIRED(SuspensionCause.CONTRACT_EXPIRED),
        CANCELLED(SuspensionCause.CONTRACT_CANCELLED);

        private final SuspensionCause cause;

        ContractSuspension(SuspensionCause cause) {
            this.cause = cause;
        }
    }

    /** Thông tin tóm tắt của tenant (hiển thị danh sách hợp đồng sắp hết hạn). */
    public record TenantBrief(UUID id, String code, String name) {
    }

    private final TenantRepository tenants;
    private final TenantModuleRepository modules;
    private final TenantRoleProvisioningPort roles;

    /** {@code roles} null nếu chỉ dùng thao tác tắt/trạng thái (không bật module). */
    public TenantModuleOperations(TenantRepository tenants, TenantModuleRepository modules,
                                  TenantRoleProvisioningPort roles) {
        this.tenants = tenants;
        this.modules = modules;
        this.roles = roles;
    }

    /** Khóa dòng tenant (SELECT ... FOR UPDATE) tới hết transaction; rỗng nếu tenant không tồn tại. */
    public Optional<TenantState> lockTenant(UUID tenantId) {
        return tenants.findByIdForUpdate(tenantId).map(TenantState::of);
    }

    /** Tên/mã tenant (không khóa). */
    public Optional<TenantBrief> brief(UUID tenantId) {
        return tenants.findById(tenantId).map(t -> new TenantBrief(t.id(), t.code(), t.name()));
    }

    /** Module đang bật, theo thứ tự enum. */
    public List<String> enabledModules(UUID tenantId) {
        return modules.findByTenant(tenantId).enabled().stream().map(ModuleCode::name).toList();
    }

    /** Bật module + chép role mẫu của module; rỗng nếu đã bật. */
    public Optional<ModuleChange> enable(UUID tenantId, String module, UUID actorId, Instant at) {
        if (roles == null) throw new IllegalStateException("Thiếu TenantRoleProvisioningPort để bật module");
        ModuleCode code = ModuleCode.parse(module);
        TenantModules current = modules.findByTenant(tenantId);
        Optional<TenantModuleEntry> changed = current.enable(code, at, actorId);
        if (changed.isEmpty()) return Optional.empty();
        modules.save(tenantId, changed.get());
        int created = roles.seedModuleTemplates(code.name());
        return Optional.of(change(changed.get(), created));
    }

    /**
     * Tắt module; rỗng nếu đang tắt.
     *
     * @throws lms.identity.tenant.domain.TenantModuleRequiredException nếu là module bật cuối cùng
     */
    public Optional<ModuleChange> disable(UUID tenantId, String module, UUID actorId, Instant at) {
        ModuleCode code = ModuleCode.parse(module);
        TenantModules current = modules.findByTenant(tenantId);
        Optional<TenantModuleEntry> changed = current.disable(code, at, actorId);
        if (changed.isEmpty()) return Optional.empty();
        modules.save(tenantId, changed.get());
        return Optional.of(change(changed.get(), 0));
    }

    /**
     * Tạm ngưng do hợp đồng; chỉ khi tenant đang ACTIVE (đang SUSPENDED thì không đổi gì, giữ nguyên nguyên nhân).
     *
     * @return true nếu đã suspend
     */
    public boolean suspendByContract(UUID tenantId, ContractSuspension cause, UUID contractId) {
        Optional<Tenant> locked = tenants.findByIdForUpdate(tenantId);
        if (locked.isEmpty()) return false;
        Tenant tenant = locked.get();
        if (!tenant.suspendByContract(cause.cause, contractId)) return false;
        tenants.save(tenant);
        return true;
    }

    /**
     * Tự kích hoạt lại tenant chỉ khi đang SUSPENDED do job hết hạn ({@code CONTRACT_EXPIRED}); nguyên nhân khác giữ
     * nguyên. Đọc nguyên nhân SAU khi khóa dòng nên không đua với suspend tay đồng thời.
     *
     * @return true nếu đã mở
     */
    public boolean activateIfSuspendedByExpiry(UUID tenantId) {
        Optional<Tenant> locked = tenants.findByIdForUpdate(tenantId);
        if (locked.isEmpty()) return false;
        Tenant tenant = locked.get();
        if (!tenant.activateIfSuspendedBy(SuspensionCause.CONTRACT_EXPIRED)) return false;
        tenants.save(tenant);
        return true;
    }

    private static ModuleChange change(TenantModuleEntry e, int rolesCreated) {
        return new ModuleChange(e.module().name(), e.enabled(), e.updatedAt(), e.updatedBy(), rolesCreated);
    }
}
