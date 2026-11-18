package lms.identity.tenantdata.application;

import java.util.UUID;
import lms.identity.tenant.application.PlatformEvents;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audit xuất dữ liệu tenant (ai yêu cầu/tải, lúc nào): event {@code PlatformOperationPerformed} lên identity-events
 * (support lưu audit) + log {@code platform-audit}. Best-effort và chỉ gọi SAU khi việc chính đã xong. {@code detail}
 * chỉ chứa mã/id, không dữ liệu tenant.
 */
final class ExportAuditor {

    static final String REQUESTED = "TENANT_DATA_EXPORT_REQUESTED";
    static final String DOWNLOADED = "TENANT_DATA_EXPORT_DOWNLOADED";
    static final String FAILED = "TENANT_DATA_EXPORT_FAILED";
    static final String EXPIRED = "TENANT_DATA_EXPORT_EXPIRED";

    private static final Logger AUDIT = LoggerFactory.getLogger("platform-audit");
    private static final Logger log = LoggerFactory.getLogger(ExportAuditor.class);

    private final PublishPlatformEventPort events;

    ExportAuditor(PublishPlatformEventPort events) {
        this.events = events;
    }

    void record(String operation, UUID actor, boolean platform, UUID tenantId, UUID exportId, String detail) {
        if (platform) {
            AUDIT.info("platform-audit operation={} actor={} tenant={} exportId={}", operation.toLowerCase(), actor,
                    tenantId, exportId);
        }
        try {
            TenantContext.runAs(tenantId, () -> events.publish(
                    PlatformEvents.operation(operation, actor, tenantId, exportId, detail)));
        } catch (RuntimeException e) {
            log.warn("Không phát được audit {} cho export {}: {}", operation, exportId, e.toString());
        }
    }
}
