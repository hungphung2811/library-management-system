package lms.identity.apiclient.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationCredential;
import lms.identity.apiclient.domain.ApplicationCredentialRepository;
import lms.identity.apiclient.domain.ApplicationKind;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.apiclient.domain.ApplicationScope;
import lms.identity.apiclient.domain.ApplicationStatus;
import lms.identity.apiclient.domain.ClientSecret;
import lms.identity.tenant.application.TenantAvailabilityQuery;
import lms.identity.tenant.application.TenantAvailabilityQuery.Availability;
import lms.identity.tenant.application.TransactionalRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xác thực client của token endpoint OAuth2 client_credentials (SaaS API Authentication, pha I2). Trả rỗng cho MỌI lý do
 * thất bại (client_id sai/không tồn tại, secret sai, credential hết hạn/thu hồi, Application không ACTIVE hoặc không phải
 * INTEGRATION, tenant không hoạt động): người gọi không phân biệt được. Giảm chênh lệch thời gian: luôn thực hiện đủ các
 * truy vấn (Application, credential, tenant) và so sánh hằng thời gian ({@link MessageDigest#isEqual}) với MỌI credential
 * (kể cả đã thu hồi) cùng một phép so với băm giả, không rẽ nhánh sớm. Lý do thật chỉ ghi log (không có secret).
 * {@code last_used_at} chỉ ghi tối đa một lần mỗi giờ mỗi credential, bằng một câu UPDATE có điều kiện.
 */
public class AuthenticateClientUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticateClientUseCase.class);
    private static final UUID NO_APPLICATION = new UUID(0L, 0L);
    private static final String DUMMY_HASH = ClientSecret.hash("lms_sk_không-tồn-tại");
    static final Duration LAST_USED_GRANULARITY = Duration.ofHours(1);

    /** Client đã xác thực: scope đã cấp, scope còn dùng được theo module của tenant, và module đang bật. */
    public record AuthenticatedClient(UUID applicationId, UUID tenantId, List<String> grantedScopes,
                                      List<String> availableScopes, List<String> modules) {
        public AuthenticatedClient {
            grantedScopes = List.copyOf(grantedScopes);
            availableScopes = List.copyOf(availableScopes);
            modules = List.copyOf(modules);
        }
    }

    private enum Failure { UNKNOWN_CLIENT, BAD_SECRET, NOT_INTEGRATION, APPLICATION_NOT_ACTIVE, TENANT_NOT_ACTIVE }

    private final ApplicationRepository applications;
    private final ApplicationCredentialRepository credentials;
    private final TenantAvailabilityQuery tenants;
    private final TransactionalRunner tx;
    private final Clock clock;

    public AuthenticateClientUseCase(ApplicationRepository applications, ApplicationCredentialRepository credentials,
                                     TenantAvailabilityQuery tenants, TransactionalRunner tx, Clock clock) {
        this.applications = applications;
        this.credentials = credentials;
        this.tenants = tenants;
        this.tx = tx;
        this.clock = clock;
    }

    public Optional<AuthenticatedClient> authenticate(String clientId, String clientSecret) {
        Instant now = clock.instant();
        UUID id = parse(clientId);
        Optional<Application> app = id == null ? Optional.empty() : applications.findVisibleById(id);
        UUID appId = app.map(Application::id).orElse(NO_APPLICATION);
        List<ApplicationCredential> all = credentials.findByApplication(appId);
        Availability tenant = tenants.execute(app.map(Application::tenantId).orElse(null));

        String presented = clientSecret == null ? "" : clientSecret;
        ApplicationCredential matched = null;
        for (ApplicationCredential c : all) {
            boolean equal = ClientSecret.matches(presented, c.secretHash());
            if (equal && c.isUsableAt(now) && matched == null) {
                matched = c;
            }
        }
        // Luôn có ít nhất một phép so sánh (cả khi không có credential nào), kết quả bị bỏ
        MessageDigest.isEqual(ClientSecret.hash(presented).getBytes(StandardCharsets.US_ASCII),
                DUMMY_HASH.getBytes(StandardCharsets.US_ASCII));

        Failure failure = null;
        if (app.isEmpty()) {
            failure = Failure.UNKNOWN_CLIENT;
        } else if (matched == null) {
            failure = Failure.BAD_SECRET;
        } else if (app.get().kind() != ApplicationKind.INTEGRATION) {
            failure = Failure.NOT_INTEGRATION;
        } else if (app.get().status() != ApplicationStatus.ACTIVE) {
            failure = Failure.APPLICATION_NOT_ACTIVE;
        } else if (!tenant.active()) {
            failure = Failure.TENANT_NOT_ACTIVE;
        }
        if (failure != null) {
            // Chỉ log lý do + applicationId (đã tồn tại); không bao giờ log secret hay client_id người dùng gõ
            LOG.warn("oauth2-token denied reason={} applicationId={}", failure, app.map(Application::id).orElse(null));
            return Optional.empty();
        }

        touch(matched, now);
        Application a = app.get();
        List<String> granted = a.scopes().stream().map(ApplicationScope::code).sorted().toList();
        List<String> available = a.scopes().stream()
                .filter(s -> s.requiredModule() == null || tenant.modules().contains(s.requiredModule()))
                .map(ApplicationScope::code).sorted().toList();
        return Optional.of(new AuthenticatedClient(a.id(), a.tenantId(), granted, available, tenant.modules()));
    }

    /** Ghi last_used_at tối đa một lần mỗi giờ; lỗi ghi không làm hỏng việc cấp token. */
    private void touch(ApplicationCredential credential, Instant now) {
        Instant last = credential.lastUsedAt();
        if (last != null && last.plus(LAST_USED_GRANULARITY).isAfter(now)) {
            return;
        }
        try {
            tx.inTransaction(() -> {
                credentials.touchLastUsed(credential.id(), now);
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warn("Không ghi được last_used_at của credential {}: {}", credential.id(), e.getClass().getSimpleName());
        }
    }

    private static UUID parse(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.length() != 36) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
