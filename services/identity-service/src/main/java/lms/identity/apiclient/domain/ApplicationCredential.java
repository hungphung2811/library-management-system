package lms.identity.apiclient.domain;

import java.time.Instant;
import java.util.UUID;
import lms.identity.apiclient.domain.ApplicationException.Reason;

/**
 * Credential (client secret) của Application INTEGRATION. Chỉ có SHA-256 của secret và gợi ý 4 ký tự cuối; không bao giờ
 * giữ bản rõ. Tối đa {@link #MAX_ACTIVE} credential dùng được cùng lúc (giai đoạn xoay vòng). Hết hạn coi như thu hồi.
 */
public class ApplicationCredential {

    public static final int MAX_ACTIVE = 2;

    private final UUID id;
    private final UUID applicationId;
    private final UUID tenantId;
    private final String secretHash;
    private final String secretHint;
    private CredentialStatus status;
    private final Instant createdAt;
    private final UUID createdBy;
    private final Instant expiresAt;
    private Instant lastUsedAt;
    private Instant revokedAt;
    private UUID revokedBy;
    private RevokeReason revokeReason;

    private ApplicationCredential(UUID id, UUID applicationId, UUID tenantId, String secretHash, String secretHint,
                                  CredentialStatus status, Instant createdAt, UUID createdBy, Instant expiresAt,
                                  Instant lastUsedAt, Instant revokedAt, UUID revokedBy, RevokeReason revokeReason) {
        this.id = id;
        this.applicationId = applicationId;
        this.tenantId = tenantId;
        this.secretHash = secretHash;
        this.secretHint = secretHint;
        this.status = status;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.lastUsedAt = lastUsedAt;
        this.revokedAt = revokedAt;
        this.revokedBy = revokedBy;
        this.revokeReason = revokeReason;
    }

    /** Cấp credential mới (secret do {@link ClientSecret#generate} sinh; chỉ hash/hint được truyền vào). */
    public static ApplicationCredential issue(Application application, ClientSecret secret, UUID actor, Instant at,
                                              Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(at)) {
            throw new ApplicationException(Reason.INVALID_APPLICATION, "Hạn dùng phải sau thời điểm tạo");
        }
        return new ApplicationCredential(UUID.randomUUID(), application.id(), application.tenantId(), secret.hash(),
                secret.hint(), CredentialStatus.ACTIVE, at, actor, expiresAt, null, null, null, null);
    }

    public static ApplicationCredential restore(UUID id, UUID applicationId, UUID tenantId, String secretHash,
                                                String secretHint, CredentialStatus status, Instant createdAt,
                                                UUID createdBy, Instant expiresAt, Instant lastUsedAt,
                                                Instant revokedAt, UUID revokedBy, RevokeReason revokeReason) {
        return new ApplicationCredential(id, applicationId, tenantId, secretHash, secretHint, status, createdAt,
                createdBy, expiresAt, lastUsedAt, revokedAt, revokedBy, revokeReason);
    }

    /** Đang ACTIVE và chưa hết hạn tại thời điểm {@code now}. */
    public boolean isUsableAt(Instant now) {
        return status == CredentialStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** @throws ApplicationException CREDENTIAL_NOT_ACTIVE nếu đã thu hồi */
    public void revoke(RevokeReason reason, UUID actor, Instant at) {
        if (status == CredentialStatus.REVOKED) {
            throw new ApplicationException(Reason.CREDENTIAL_NOT_ACTIVE, "Credential đã bị thu hồi");
        }
        status = CredentialStatus.REVOKED;
        revokedAt = at;
        revokedBy = actor;
        revokeReason = reason;
    }

    /** @throws ApplicationException CREDENTIAL_LIMIT_EXCEEDED nếu đã có đủ {@link #MAX_ACTIVE} credential dùng được */
    public static void requireRoomForNew(java.util.Collection<ApplicationCredential> existing, Instant now) {
        long usable = existing.stream().filter(c -> c.isUsableAt(now)).count();
        if (usable >= MAX_ACTIVE) {
            throw new ApplicationException(Reason.CREDENTIAL_LIMIT_EXCEEDED,
                    "Đã có " + MAX_ACTIVE + " credential đang dùng; thu hồi một credential cũ trước khi tạo mới");
        }
    }

    public UUID id() { return id; }
    public UUID applicationId() { return applicationId; }
    public UUID tenantId() { return tenantId; }
    public String secretHash() { return secretHash; }
    public String secretHint() { return secretHint; }
    public CredentialStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public UUID createdBy() { return createdBy; }
    public Instant expiresAt() { return expiresAt; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public Instant revokedAt() { return revokedAt; }
    public UUID revokedBy() { return revokedBy; }
    public RevokeReason revokeReason() { return revokeReason; }
}
