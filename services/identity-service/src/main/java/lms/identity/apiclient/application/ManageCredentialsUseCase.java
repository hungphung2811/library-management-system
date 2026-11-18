package lms.identity.apiclient.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationViews.CreatedCredential;
import lms.identity.apiclient.application.ApplicationViews.CredentialView;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationCredential;
import lms.identity.apiclient.domain.ApplicationCredentialRepository;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationHistoryAction;
import lms.identity.apiclient.domain.ApplicationHistoryRepository;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.apiclient.domain.ClientSecret;
import lms.identity.apiclient.domain.RevokeReason;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.TransactionalRunner;

/**
 * Tạo và thu hồi credential của Application INTEGRATION. Xoay vòng = tạo credential mới (đồng thời ACTIVE tối đa 2, kiểm
 * dưới khóa dòng Application) rồi thu hồi credential cũ sau khi client đã đổi. Secret (D7): 32 byte CSPRNG, chỉ lưu
 * SHA-256 + 4 ký tự cuối; bản đầy đủ chỉ nằm trong {@link CreatedCredential} trả đúng một lần, không vào lịch sử, event
 * hay log. Hạn dùng mặc định theo {@link ApplicationPolicy} (Q13: 365 ngày).
 */
public class ManageCredentialsUseCase {

    private final ApplicationRepository applications;
    private final ApplicationCredentialRepository credentials;
    private final ApplicationAuditor auditor;
    private final TransactionalRunner tx;
    private final ApplicationPolicy policy;
    private final SecureRandom random;
    private final Clock clock;

    public ManageCredentialsUseCase(ApplicationRepository applications, ApplicationCredentialRepository credentials,
                                    ApplicationHistoryRepository history, PublishPlatformEventPort events,
                                    TransactionalRunner tx, ApplicationPolicy policy, SecureRandom random,
                                    Clock clock) {
        this.applications = applications;
        this.credentials = credentials;
        this.auditor = new ApplicationAuditor(history, events);
        this.tx = tx;
        this.policy = policy;
        this.random = random;
        this.clock = clock;
    }

    /**
     * @param expiresInDays số ngày hiệu lực (1 đến hạn tối đa cấu hình); null = mặc định cấu hình
     * @throws ApplicationException CREDENTIAL_LIMIT_EXCEEDED khi đã có 2 credential dùng được
     */
    public CreatedCredential create(UUID tenantId, UUID applicationId, Integer expiresInDays, UUID actorId) {
        Duration ttl = ttl(expiresInDays);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ClientSecret secret = ClientSecret.generate(random);
        ApplicationCredential credential = tx.inTransaction(() -> {
            Application app = applications.findByIdAndTenantForUpdate(applicationId, tenantId).orElseThrow(
                    () -> new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng"));
            app.requireCanIssueCredential();
            ApplicationCredential.requireRoomForNew(credentials.findByApplication(app.id()), now);
            ApplicationCredential issued = ApplicationCredential.issue(app, secret, actorId, now, now.plus(ttl));
            credentials.save(issued);
            auditor.record(tenantId, app.id(), issued.id(), ApplicationHistoryAction.CREDENTIAL_CREATED,
                    "hint=" + issued.secretHint() + " expiresAt=" + issued.expiresAt(), actorId, now);
            return issued;
        });
        auditor.announce(tenantId, applicationId, ApplicationHistoryAction.CREDENTIAL_CREATED,
                "credentialId=" + credential.id(), actorId);
        return new CreatedCredential(credential.id(), applicationId, secret.plain(), credential.secretHint(),
                credential.createdAt(), credential.expiresAt());
    }

    /** Thu hồi có hiệu lực ngay với lần xin token kế tiếp (pha I2); credential của Application khác là NOT_FOUND. */
    public CredentialView revoke(UUID tenantId, UUID applicationId, UUID credentialId, String reason, UUID actorId) {
        RevokeReason why = parseReason(reason);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ApplicationCredential revoked = tx.inTransaction(() -> {
            Application app = applications.findByIdAndTenantForUpdate(applicationId, tenantId).orElseThrow(
                    () -> new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng"));
            ApplicationCredential credential = credentials.findByIdAndApplication(credentialId, app.id()).orElseThrow(
                    () -> new ApplicationException(Reason.CREDENTIAL_NOT_FOUND, "Không tìm thấy credential"));
            credential.revoke(why, actorId, now);
            credentials.save(credential);
            auditor.record(tenantId, app.id(), credential.id(), ApplicationHistoryAction.CREDENTIAL_REVOKED,
                    "hint=" + credential.secretHint() + " reason=" + why.name(), actorId, now);
            return credential;
        });
        auditor.announce(tenantId, applicationId, ApplicationHistoryAction.CREDENTIAL_REVOKED,
                "credentialId=" + revoked.id() + " reason=" + why.name(), actorId);
        return CredentialView.of(revoked, now);
    }

    public List<CredentialView> list(UUID tenantId, UUID applicationId) {
        Instant now = clock.instant();
        Application app = applications.findByIdAndTenant(applicationId, tenantId).orElseThrow(
                () -> new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng"));
        return credentials.findByApplication(app.id()).stream().map(c -> CredentialView.of(c, now)).toList();
    }

    private Duration ttl(Integer expiresInDays) {
        if (expiresInDays == null) return policy.defaultCredentialTtl();
        Duration requested = Duration.ofDays(expiresInDays);
        if (expiresInDays < 1 || requested.compareTo(policy.maxCredentialTtl()) > 0) {
            throw new ApplicationException(Reason.INVALID_APPLICATION,
                    "expiresInDays phải từ 1 đến " + policy.maxCredentialTtl().toDays());
        }
        return requested;
    }

    private static RevokeReason parseReason(String raw) {
        if (raw == null || raw.isBlank()) return RevokeReason.MANUAL;
        try {
            RevokeReason reason = RevokeReason.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            if (reason == RevokeReason.APPLICATION_DELETED) throw new IllegalArgumentException();
            return reason;
        } catch (IllegalArgumentException e) {
            throw new ApplicationException(Reason.INVALID_APPLICATION, "reason phải là ROTATED, MANUAL hoặc COMPROMISED");
        }
    }
}
