package lms.identity.apiclient.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationCredential;
import lms.identity.apiclient.domain.ApplicationCredentialRepository;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationHistoryAction;
import lms.identity.apiclient.domain.ApplicationHistoryRepository;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.apiclient.domain.ApplicationScope;
import lms.identity.apiclient.domain.RevokeReason;
import lms.identity.apiclient.domain.SiteKey;
import lms.identity.tenant.application.ManageApplicationOriginsUseCase;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.TransactionalRunner;

/**
 * Sửa vòng đời Application của MỘT tenant: sửa, tạm ngưng, kích hoạt, thu hồi (xóa mềm), thay scope, xoay site key.
 * Mỗi thao tác trong MỘT transaction: khóa dòng Application theo (id, tenantId) (id của tenant khác là
 * APPLICATION_NOT_FOUND), đổi, ghi application_history; sau commit phát event audit. Xóa mềm thu hồi mọi credential
 * (APPLICATION_DELETED) và xóa domain allowlist của Application trong cùng transaction.
 */
public class ManageApplicationUseCase {

    private final ApplicationRepository applications;
    private final ApplicationCredentialRepository credentials;
    private final ManageApplicationOriginsUseCase origins;
    private final ApplicationAuditor auditor;
    private final TransactionalRunner tx;
    private final SecureRandom random;
    private final Clock clock;
    private final ApplicationRevocationPort revocation;

    public ManageApplicationUseCase(ApplicationRepository applications, ApplicationCredentialRepository credentials,
                                    ApplicationHistoryRepository history, ManageApplicationOriginsUseCase origins,
                                    PublishPlatformEventPort events, TransactionalRunner tx, SecureRandom random,
                                    Clock clock) {
        this(applications, credentials, history, origins, events, tx, random, clock, ApplicationRevocationPort.NONE);
    }

    /** {@code revocation}: thu hồi token tức thời khi tạm ngưng hoặc xóa Application (sau commit, best-effort). */
    public ManageApplicationUseCase(ApplicationRepository applications, ApplicationCredentialRepository credentials,
                                    ApplicationHistoryRepository history, ManageApplicationOriginsUseCase origins,
                                    PublishPlatformEventPort events, TransactionalRunner tx, SecureRandom random,
                                    Clock clock, ApplicationRevocationPort revocation) {
        this.revocation = revocation;
        this.applications = applications;
        this.credentials = credentials;
        this.origins = origins;
        this.auditor = new ApplicationAuditor(history, events);
        this.tx = tx;
        this.random = random;
        this.clock = clock;
    }

    public ApplicationDetail update(UUID tenantId, UUID applicationId, String name, Integer rateLimitPerMinute,
                                    UUID actorId) {
        return change(tenantId, applicationId, actorId, ApplicationHistoryAction.UPDATED, (app, at) -> {
            String trimmed = name == null ? "" : name.trim();
            if (applications.existsName(tenantId, trimmed, app.id())) {
                throw new ApplicationException(Reason.APPLICATION_NAME_DUPLICATED, "Tên ứng dụng đã được dùng");
            }
            app.update(name, rateLimitPerMinute, actorId, at);
            return "name=" + app.name() + " rateLimitPerMinute=" + app.rateLimitPerMinute();
        });
    }

    public ApplicationDetail suspend(UUID tenantId, UUID applicationId, UUID actorId) {
        return change(tenantId, applicationId, actorId, ApplicationHistoryAction.SUSPENDED, (app, at) -> {
            app.suspend(actorId, at);
            return null;
        });
    }

    public ApplicationDetail reactivate(UUID tenantId, UUID applicationId, UUID actorId) {
        return change(tenantId, applicationId, actorId, ApplicationHistoryAction.REACTIVATED, (app, at) -> {
            app.reactivate(actorId, at);
            return null;
        });
    }

    /** Thay toàn bộ scope; scope không có trong danh mục hoặc chưa được cấp ở pha này bị từ chối (không đổi gì). */
    public ApplicationDetail replaceScopes(UUID tenantId, UUID applicationId, List<String> requested, UUID actorId) {
        List<ApplicationScope> parsed = (requested == null ? List.<String>of() : requested).stream()
                .map(ApplicationScope::parse).distinct().toList();
        return change(tenantId, applicationId, actorId, ApplicationHistoryAction.SCOPES_CHANGED, (app, at) -> {
            app.replaceScopes(parsed, actorId, at);
            return "scopes=" + parsed.stream().map(ApplicationScope::code).sorted().collect(Collectors.joining(","));
        });
    }

    /** Thay site key ngay; key cũ hết hiệu lực (không có giai đoạn chồng lấp). Lịch sử không ghi giá trị key. */
    public ApplicationDetail rotateSiteKey(UUID tenantId, UUID applicationId, UUID actorId) {
        return change(tenantId, applicationId, actorId, ApplicationHistoryAction.SITE_KEY_ROTATED, (app, at) -> {
            String candidate = null;
            for (int attempt = 0; attempt < 5 && candidate == null; attempt++) {
                String next = SiteKey.generate(random);
                if (!applications.existsSiteKey(next)) candidate = next;
            }
            if (candidate == null) throw new IllegalStateException("Không sinh được site key duy nhất");
            app.rotateSiteKey(candidate, actorId, at);
            return null;
        });
    }

    /** Thu hồi (xóa mềm) Application: credential bị thu hồi, domain bị xóa, site key không còn dùng được. */
    public void delete(UUID tenantId, UUID applicationId, UUID actorId) {
        Instant now = now();
        Application deleted = tx.inTransaction(() -> {
            Application app = lock(tenantId, applicationId);
            app.delete(actorId, now);
            applications.save(app);
            List<ApplicationCredential> all = credentials.findByApplication(app.id());
            int revoked = 0;
            for (ApplicationCredential c : all) {
                if (c.status() == lms.identity.apiclient.domain.CredentialStatus.ACTIVE) {
                    c.revoke(RevokeReason.APPLICATION_DELETED, actorId, now);
                    credentials.save(c);
                    revoked++;
                }
            }
            List<String> removedDomains = origins.removeAll(tenantId, app.id(), actorId);
            auditor.record(tenantId, app.id(), null, ApplicationHistoryAction.DELETED,
                    "revokedCredentials=" + revoked + " removedDomains=" + removedDomains.size(), actorId, now);
            return app;
        });
        auditor.announce(tenantId, deleted.id(), ApplicationHistoryAction.DELETED, null, actorId);
        revocation.revokeTokensIssuedBefore(deleted.id(), now);
    }

    /** Thân thay đổi trả về chuỗi chi tiết cho lịch sử (null = không có), không bao giờ chứa secret. */
    private interface Mutation {
        String apply(Application app, Instant at);
    }

    private ApplicationDetail change(UUID tenantId, UUID applicationId, UUID actorId,
                                     ApplicationHistoryAction action, Mutation mutation) {
        Instant now = now();
        Application changed = tx.inTransaction(() -> {
            Application app = lock(tenantId, applicationId);
            String detail = mutation.apply(app, now);
            applications.save(app);
            auditor.record(tenantId, app.id(), null, action, detail, actorId, now);
            return app;
        });
        auditor.announce(tenantId, changed.id(), action, null, actorId);
        if (action == ApplicationHistoryAction.SUSPENDED) revocation.revokeTokensIssuedBefore(changed.id(), now);
        return ApplicationViews.detail(changed, credentials.findByApplication(changed.id()), now);
    }

    private Application lock(UUID tenantId, UUID applicationId) {
        return applications.findByIdAndTenantForUpdate(applicationId, tenantId).orElseThrow(
                () -> new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
