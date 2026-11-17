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
import lms.identity.tenant.domain.TenantModuleRequiredException;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.UnknownModuleException;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform admin tắt module của tenant (ADR-0009, DELETE /api/platform/tenants/{id}/modules/{module}). Chỉ đổi
 * {@code enabled=false}: KHÔNG xóa role, dữ liệu nghiệp vụ hay menu. Tắt module bật cuối cùng bị từ chối. Tắt module
 * đang tắt là no-op. Token đã phát vẫn dùng module đó tối đa một TTL access token (15 phút); cần dừng ngay thì suspend
 * tenant. Sau commit phát {@code TenantModuleDisabled} và audit (best-effort) kèm log {@code platform-audit}. Phần lõi
 * ở {@link TenantModuleOperations}; thay đổi thật được ghi nhận ngoại lệ ngoài hợp đồng qua
 * {@link ManualModuleChangeRecorder} (nếu có) trong cùng transaction (ADR-0012).
 */
public class DisableTenantModuleUseCase {

    private static final Logger AUDIT = LoggerFactory.getLogger("platform-audit");

    private final TenantRepository tenants;
    private final TenantModuleRepository modules;
    private final TenantModuleOperations operations;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;
    private final ManualModuleChangeRecorder recorder;

    public DisableTenantModuleUseCase(TenantRepository tenants, TenantModuleRepository modules,
                                      PublishPlatformEventPort events, TransactionalRunner tx) {
        this(tenants, modules, events, tx, null);
    }

    public DisableTenantModuleUseCase(TenantRepository tenants, TenantModuleRepository modules,
                                      PublishPlatformEventPort events, TransactionalRunner tx,
                                      ManualModuleChangeRecorder recorder) {
        this.tenants = tenants;
        this.modules = modules;
        this.operations = new TenantModuleOperations(tenants, modules, null);
        this.events = events;
        this.tx = tx;
        this.recorder = recorder;
    }

    /**
     * @throws UnknownModuleException nếu mã module sai
     * @throws TenantNotFoundException nếu tenant không tồn tại
     * @throws TenantModuleRequiredException nếu là module bật cuối cùng
     */
    public TenantModuleResponse execute(UUID tenantId, String moduleCode, UUID actorId) {
        ModuleCode module = ModuleCode.parse(moduleCode);
        tenants.findById(tenantId).orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
        return TenantContext.callAs(tenantId, () -> {
            record Result(TenantModuleEntry entry, boolean changed) {
            }
            Result result = tx.inTransaction(() -> {
                operations.lockTenant(tenantId)
                        .orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
                Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
                Optional<ModuleChange> changed = operations.disable(tenantId, module.name(), actorId, at);
                if (changed.isEmpty()) return new Result(modules.findByTenant(tenantId).entry(module), false);
                if (recorder != null) recorder.recordManualChange(tenantId, module.name(), false, actorId, at);
                ModuleChange c = changed.get();
                return new Result(new TenantModuleEntry(module, false, c.updatedAt(), c.updatedBy()), true);
            });
            if (result.changed()) {
                events.publish(new TenantModuleEvents.Disabled(tenantId, module, actorId));
                events.publish(PlatformOperationEvent.of("TENANT_MODULE_DISABLED", actorId, tenantId, tenantId,
                        "module=" + module));
                AUDIT.info("platform-audit operation=tenant-module-disabled actor={} tenant={} module={}", actorId,
                        tenantId, module);
            }
            return TenantModuleResponse.from(result.entry());
        });
    }
}
