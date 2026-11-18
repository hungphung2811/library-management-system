package lms.identity.apiclient.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationViews.DomainList;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationHistoryAction;
import lms.identity.apiclient.domain.ApplicationHistoryRepository;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.tenant.application.ManageApplicationOriginsUseCase;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.StorefrontOriginResponse;
import lms.identity.tenant.application.TransactionalRunner;

/**
 * Domain allowlist của Application website: giao cho {@link ManageApplicationOriginsUseCase} của context tenant (tái dùng
 * luật chuẩn hóa/kiểm origin, giới hạn 10 origin mỗi tenant, UNIQUE toàn hệ thống của ADR-0013). Ở đây xác nhận
 * Application thuộc tenant và là PUBLIC_SITE, rồi ghi application_history cùng transaction. Domain allowlist chỉ là
 * CORS, không phải bằng chứng xác thực.
 */
public class ManageApplicationDomainsUseCase {

    private final ApplicationRepository applications;
    private final ManageApplicationOriginsUseCase origins;
    private final ApplicationAuditor auditor;
    private final TransactionalRunner tx;
    private final Clock clock;

    public ManageApplicationDomainsUseCase(ApplicationRepository applications, ManageApplicationOriginsUseCase origins,
                                           ApplicationHistoryRepository history, PublishPlatformEventPort events,
                                           TransactionalRunner tx, Clock clock) {
        this.applications = applications;
        this.origins = origins;
        this.auditor = new ApplicationAuditor(history, events);
        this.tx = tx;
        this.clock = clock;
    }

    public DomainList list(UUID tenantId, UUID applicationId) {
        Application app = find(tenantId, applicationId);
        app.requireWebsite();
        return new DomainList(origins.maxOriginsPerTenant(), origins.list(tenantId, app.id()));
    }

    public StorefrontOriginResponse add(UUID tenantId, UUID applicationId, String origin, UUID actorId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        StorefrontOriginResponse added = tx.inTransaction(() -> {
            Application app = lock(tenantId, applicationId);
            app.requireWebsite();
            app.requireActive();
            StorefrontOriginResponse created = origins.add(tenantId, app.id(), origin, actorId);
            auditor.record(tenantId, app.id(), null, ApplicationHistoryAction.DOMAIN_ADDED,
                    "origin=" + created.origin(), actorId, now);
            return created;
        });
        auditor.announce(tenantId, applicationId, ApplicationHistoryAction.DOMAIN_ADDED, "origin=" + added.origin(),
                actorId);
        return added;
    }

    public void remove(UUID tenantId, UUID applicationId, UUID originId, UUID actorId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String removed = tx.inTransaction(() -> {
            Application app = lock(tenantId, applicationId);
            app.requireWebsite();
            String value = origins.remove(tenantId, app.id(), originId, actorId);
            auditor.record(tenantId, app.id(), null, ApplicationHistoryAction.DOMAIN_REMOVED, "origin=" + value,
                    actorId, now);
            return value;
        });
        auditor.announce(tenantId, applicationId, ApplicationHistoryAction.DOMAIN_REMOVED, "origin=" + removed,
                actorId);
    }

    private Application find(UUID tenantId, UUID applicationId) {
        return applications.findByIdAndTenant(applicationId, tenantId).orElseThrow(
                () -> new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng"));
    }

    private Application lock(UUID tenantId, UUID applicationId) {
        return applications.findByIdAndTenantForUpdate(applicationId, tenantId).orElseThrow(
                () -> new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng"));
    }
}
