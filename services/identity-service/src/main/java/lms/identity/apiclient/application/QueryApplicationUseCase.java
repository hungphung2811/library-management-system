package lms.identity.apiclient.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.application.ApplicationViews.ApplicationSummary;
import lms.identity.apiclient.application.ApplicationViews.HistoryItem;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationCredential;
import lms.identity.apiclient.domain.ApplicationCredentialRepository;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationHistoryRepository;
import lms.identity.apiclient.domain.ApplicationRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc Application của MỘT tenant (tenant admin: tenantId từ TenantContext; platform admin: tenantId từ đường dẫn). Mọi
 * truy vấn kèm tenantId; id của tenant khác là APPLICATION_NOT_FOUND, cùng thân với "không tồn tại". Danh sách nạp
 * scope và credential theo lô (không N+1).
 */
public class QueryApplicationUseCase {

    public static final int HISTORY_LIMIT = 200;

    private final ApplicationRepository applications;
    private final ApplicationCredentialRepository credentials;
    private final ApplicationHistoryRepository history;
    private final Clock clock;

    public QueryApplicationUseCase(ApplicationRepository applications, ApplicationCredentialRepository credentials,
                                   ApplicationHistoryRepository history, Clock clock) {
        this.applications = applications;
        this.credentials = credentials;
        this.history = history;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ApplicationSummary> list(UUID tenantId) {
        Instant now = clock.instant();
        List<Application> rows = applications.findByTenant(tenantId);
        Map<UUID, List<ApplicationCredential>> byApp = groupByApplication(
                credentials.findByApplications(rows.stream().map(Application::id).toList()));
        return rows.stream().map(a -> ApplicationViews.summary(a, byApp.getOrDefault(a.id(), List.of()), now)).toList();
    }

    @Transactional(readOnly = true)
    public ApplicationDetail get(UUID tenantId, UUID applicationId) {
        Application app = require(tenantId, applicationId);
        return ApplicationViews.detail(app, credentials.findByApplication(app.id()), clock.instant());
    }

    @Transactional(readOnly = true)
    public List<HistoryItem> history(UUID tenantId, UUID applicationId) {
        Application app = require(tenantId, applicationId);
        return history.findByApplication(tenantId, app.id(), HISTORY_LIMIT).stream().map(HistoryItem::of).toList();
    }

    private Application require(UUID tenantId, UUID applicationId) {
        return applications.findByIdAndTenant(applicationId, tenantId).orElseThrow(
                () -> new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng"));
    }

    private static Map<UUID, List<ApplicationCredential>> groupByApplication(Collection<ApplicationCredential> all) {
        return all.stream().collect(Collectors.groupingBy(ApplicationCredential::applicationId, HashMap::new,
                Collectors.toList()));
    }
}
