package lms.identity.apiclient.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationHistoryAction;
import lms.identity.apiclient.domain.ApplicationKind;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.apiclient.domain.SiteKey;
import lms.identity.tenant.application.TransactionalRunner;

/**
 * Tenant admin tạo Application (Q3). INTEGRATION chỉ tạo được khi tenant có hợp đồng ACTIVE ({@link TenantContractGate});
 * PUBLIC_SITE nhận site key sinh tự động. Trong MỘT transaction: khóa các Application của tenant, kiểm giới hạn số
 * lượng (mềm khi tenant chưa có dòng nào: không có dòng để khóa) và tên duy nhất, ghi Application + application_history.
 * {@code tenantId} do người gọi lấy từ TenantContext (không từ client).
 */
public class CreateApplicationUseCase {

    public record Command(String name, String kind, Integer rateLimitPerMinute) {
    }

    private final ApplicationRepository applications;
    private final ApplicationAuditor auditor;
    private final TenantContractGate contracts;
    private final TransactionalRunner tx;
    private final ApplicationPolicy policy;
    private final SecureRandom random;
    private final Clock clock;

    public CreateApplicationUseCase(ApplicationRepository applications,
                                    lms.identity.apiclient.domain.ApplicationHistoryRepository history,
                                    lms.identity.tenant.application.PublishPlatformEventPort events,
                                    TenantContractGate contracts, TransactionalRunner tx, ApplicationPolicy policy,
                                    SecureRandom random, Clock clock) {
        this.applications = applications;
        this.auditor = new ApplicationAuditor(history, events);
        this.contracts = contracts;
        this.tx = tx;
        this.policy = policy;
        this.random = random;
        this.clock = clock;
    }

    public ApplicationDetail execute(UUID tenantId, Command command, UUID actorId) {
        ApplicationKind kind = parseKind(command.kind());
        if (kind == ApplicationKind.INTEGRATION && !contracts.hasActiveContract(tenantId)) {
            throw new ApplicationException(Reason.CONTRACT_REQUIRED,
                    "Cần hợp đồng thuê dịch vụ đang hiệu lực để tạo ứng dụng tích hợp");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Application saved = tx.inTransaction(() -> {
            if (applications.lockAndCountByTenant(tenantId) >= policy.maxApplicationsPerTenant()) {
                throw new ApplicationException(Reason.APPLICATION_LIMIT_EXCEEDED,
                        "Mỗi tenant tối đa " + policy.maxApplicationsPerTenant() + " ứng dụng");
            }
            String name = command.name() == null ? "" : command.name().trim();
            if (applications.existsName(tenantId, name, null)) {
                throw new ApplicationException(Reason.APPLICATION_NAME_DUPLICATED, "Tên ứng dụng đã được dùng");
            }
            String siteKey = kind == ApplicationKind.PUBLIC_SITE ? newSiteKey() : null;
            Application created = Application.create(tenantId, name, kind, command.rateLimitPerMinute(), siteKey,
                    actorId, now);
            applications.save(created);
            auditor.record(tenantId, created.id(), null, ApplicationHistoryAction.CREATED,
                    "kind=" + kind.name() + " name=" + created.name(), actorId, now);
            return created;
        });
        auditor.announce(tenantId, saved.id(), ApplicationHistoryAction.CREATED, "kind=" + kind.name(), actorId);
        return ApplicationViews.detail(saved, List.of(), now);
    }

    private String newSiteKey() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = SiteKey.generate(random);
            if (!applications.existsSiteKey(candidate)) return candidate;
        }
        throw new IllegalStateException("Không sinh được site key duy nhất");
    }

    private static ApplicationKind parseKind(String raw) {
        try {
            return ApplicationKind.valueOf(raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApplicationException(Reason.INVALID_APPLICATION,
                    "Loại ứng dụng phải là PUBLIC_SITE hoặc INTEGRATION");
        }
    }
}
