package lms.identity.apiclient.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationCredential;
import lms.identity.apiclient.domain.ApplicationHistoryEntry;
import lms.identity.apiclient.domain.ApplicationScope;
import lms.identity.tenant.application.StorefrontOriginResponse;

/** DTO của use case. Không có trường nào chứa secret ngoại trừ {@link CreatedCredential} (trả đúng một lần). */
public final class ApplicationViews {

    private ApplicationViews() {
    }

    /** Credential để liệt kê: chỉ gợi ý 4 ký tự cuối, không có hash. */
    public record CredentialView(UUID id, String secretHint, String status, boolean usable, Instant createdAt,
                                 UUID createdBy, Instant expiresAt, Instant lastUsedAt, Instant revokedAt,
                                 String revokeReason) {
        static CredentialView of(ApplicationCredential c, Instant now) {
            return new CredentialView(c.id(), c.secretHint(), c.status().name(), c.isUsableAt(now), c.createdAt(),
                    c.createdBy(), c.expiresAt(), c.lastUsedAt(), c.revokedAt(),
                    c.revokeReason() == null ? null : c.revokeReason().name());
        }
    }

    /** Tóm tắt trong danh sách. */
    public record ApplicationSummary(UUID id, String name, String kind, String status, String siteKey,
                                     Integer rateLimitPerMinute, List<String> scopes, int usableCredentials,
                                     Instant createdAt, Instant updatedAt) {
    }

    /** Chi tiết một Application (kèm credential, chưa có secret). */
    public record ApplicationDetail(UUID id, UUID clientId, String name, String kind, String status, String siteKey,
                                    Integer rateLimitPerMinute, List<String> scopes, List<CredentialView> credentials,
                                    Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {
    }

    /**
     * Phản hồi tạo/xoay credential: {@code clientSecret} chỉ xuất hiện ở đây, đúng một lần. Controller phải gắn
     * {@code Cache-Control: no-store}; không được log đối tượng này. {@code toString} đã che secret.
     */
    public record CreatedCredential(UUID credentialId, UUID clientId, String clientSecret, String secretHint,
                                    Instant createdAt, Instant expiresAt) {
        @Override
        public String toString() {
            return "CreatedCredential[credentialId=" + credentialId + ", clientId=" + clientId + ", secretHint="
                    + secretHint + "]";
        }
    }

    public record HistoryItem(UUID id, UUID credentialId, String action, String detail, UUID actorId,
                              Instant occurredAt) {
        static HistoryItem of(ApplicationHistoryEntry e) {
            return new HistoryItem(e.id(), e.credentialId(), e.action().name(), e.detail(), e.actorId(),
                    e.occurredAt());
        }
    }

    public record DomainList(int maxOriginsPerTenant, List<StorefrontOriginResponse> domains) {
    }

    static List<String> scopeCodes(Application a) {
        return a.scopes().stream().map(ApplicationScope::code).sorted().toList();
    }

    static ApplicationSummary summary(Application a, List<ApplicationCredential> credentials, Instant now) {
        return new ApplicationSummary(a.id(), a.name(), a.kind().name(), a.status().name(), a.siteKey(),
                a.rateLimitPerMinute(), scopeCodes(a), (int) credentials.stream().filter(c -> c.isUsableAt(now)).count(),
                a.createdAt(), a.updatedAt());
    }

    static ApplicationDetail detail(Application a, List<ApplicationCredential> credentials, Instant now) {
        List<CredentialView> views = credentials.stream().sorted(Comparator.comparing(ApplicationCredential::createdAt))
                .map(c -> CredentialView.of(c, now)).toList();
        return new ApplicationDetail(a.id(), a.id(), a.name(), a.kind().name(), a.status().name(), a.siteKey(),
                a.rateLimitPerMinute(), scopeCodes(a), views, a.createdAt(), a.createdBy(), a.updatedAt(),
                a.updatedBy());
    }
}
