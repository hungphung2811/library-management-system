package lms.identity.tenant.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.application.TenantModuleOperations.ModuleChange;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.TenantModuleEntry;
import lms.identity.tenant.domain.TenantModuleEvents;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.UnknownModuleException;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform admin bật module cho tenant (ADR-0009, PUT /api/platform/tenants/{id}/modules/{module}). Trong
 * {@code TenantContext.callAs(tenantId)} và MỘT transaction của identity: khóa dòng tenant, ghi tenant_modules, chép
 * role mẫu của module thành role của tenant (bỏ qua role đã có cùng tên: idempotent, không ghi đè chỉnh sửa của quản trị
 * tenant). Bật module đang bật là no-op (không ghi, không phát event). Sau commit phát {@code TenantModuleEnabled} và
 * audit (best-effort) kèm log {@code platform-audit}. Không service nào cần nghe event để hoạt động đúng. Phần lõi nằm ở
 * {@link TenantModuleOperations} (dùng chung với hợp đồng, ADR-0012); khi có thay đổi thật thì ghi nhận ngoại lệ ngoài
 * hợp đồng qua {@link ManualModuleChangeRecorder} (nếu có) trong cùng transaction.
 */
public class EnableTenantModuleUseCase {

    private static final Logger AUDIT = LoggerFactory.getLogger("platform-audit");

    private final TenantRepository tenants;
    private final TenantModuleRepository modules;
    private final TenantModuleOperations operations;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;
    private final ManualModuleChangeRecorder recorder;

    public EnableTenantModuleUseCase(TenantRepository tenants, TenantModuleRepository modules,
                                     TenantRoleProvisioningPort roles, PublishPlatformEventPort events,
                                     TransactionalRunner tx) {
        this(tenants, modules, roles, events, tx, null);
    }

    public EnableTenantModuleUseCase(TenantRepository tenants, TenantModuleRepository modules,
                                     TenantRoleProvisioningPort roles, PublishPlatformEventPort events,
                                     TransactionalRunner tx, ManualModuleChangeRecorder recorder) {
        this.tenants = tenants;
        this.modules = modules;
        this.operations = new TenantModuleOperations(tenants, modules, roles);
        this.events = events;
        this.tx = tx;
        this.recorder = recorder;
    }

    /**
     * @throws UnknownModuleException nếu mã module sai
     * @throws TenantNotFoundException nếu tenant không tồn tại
     */
    public TenantModuleResponse execute(UUID tenantId, String moduleCode, UUID actorId) {
        ModuleCode module = ModuleCode.parse(moduleCode);
        tenants.findById(tenantId).orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
        return TenantContext.callAs(tenantId, () -> {
            record Result(TenantModuleEntry entry, boolean changed, int rolesCreated) {
            }
            Result result = tx.inTransaction(() -> {
                // khóa dòng tenant: hai thao tác bật/tắt đồng thời trên cùng tenant tuần tự hóa (bất biến "còn ít nhất một")
                operations.lockTenant(tenantId)
                        .orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
                Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
                Optional<ModuleChange> changed = operations.enable(tenantId, module.name(), actorId, at);
                if (changed.isEmpty()) return new Result(modules.findByTenant(tenantId).entry(module), false, 0);
                if (recorder != null) recorder.recordManualChange(tenantId, module.name(), true, actorId, at);
                ModuleChange c = changed.get();
                return new Result(new TenantModuleEntry(module, true, c.updatedAt(), c.updatedBy()), true,
                        c.rolesCreated());
            });
            if (result.changed()) {
                events.publish(new TenantModuleEvents.Enabled(tenantId, module, actorId));
                events.publish(PlatformOperationEvent.of("TENANT_MODULE_ENABLED", actorId, tenantId, tenantId,
                        "module=" + module + ", rolesCreated=" + result.rolesCreated()));
                AUDIT.info("platform-audit operation=tenant-module-enabled actor={} tenant={} module={} rolesCreated={}",
                        actorId, tenantId, module, result.rolesCreated());
            }
            return TenantModuleResponse.from(result.entry());
        });
    }
}
