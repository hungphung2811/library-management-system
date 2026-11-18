package lms.identity.apiclient.application;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lms.identity.apiclient.domain.ApplicationHistoryAction;
import lms.identity.apiclient.domain.ApplicationHistoryEntry;
import lms.identity.apiclient.domain.ApplicationHistoryRepository;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.PlatformEvents;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dấu vết mọi thay đổi Application: dòng application_history (cùng transaction với thay đổi, chỉ thêm) và, sau commit,
 * event audit nền tảng + log {@code platform-audit} (best-effort, chỉ là bản sao). {@code detail} KHÔNG BAO GIỜ chứa
 * secret: người gọi chỉ đưa vào hint, scope, origin, lý do.
 */
final class ApplicationAuditor {

    private static final Logger AUDIT = LoggerFactory.getLogger("platform-audit");

    private final ApplicationHistoryRepository history;
    private final PublishPlatformEventPort events;

    ApplicationAuditor(ApplicationHistoryRepository history, PublishPlatformEventPort events) {
        this.history = history;
        this.events = events;
    }

    /** Gọi TRONG transaction của thay đổi. */
    void record(UUID tenantId, UUID applicationId, UUID credentialId, ApplicationHistoryAction action, String detail,
                UUID actor, Instant at) {
        history.append(ApplicationHistoryEntry.of(tenantId, applicationId, credentialId, action, detail, actor, at));
    }

    /** Gọi SAU commit. */
    void announce(UUID tenantId, UUID applicationId, ApplicationHistoryAction action, String detail, UUID actor) {
        AUDIT.info("platform-audit operation=application-{} actor={} tenant={} applicationId={}",
                action.name().toLowerCase(Locale.ROOT), actor, tenantId, applicationId);
        // Envelope lấy tenantId từ TenantContext (như các use case platform khác)
        TenantContext.runAs(tenantId, () -> events.publish(
                PlatformEvents.operation("APPLICATION_" + action.name(), actor, tenantId, applicationId, detail)));
    }
}
