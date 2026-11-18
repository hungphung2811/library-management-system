package lms.identity.apiclient.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lms.identity.apiclient.domain.ApplicationException.Reason;

/**
 * Một Application của tenant (website công khai hoặc backend tích hợp). Id đồng thời là client_id (và sub của token
 * integration, pha I2). Chỉ chỉnh sửa khi ACTIVE; DELETED là xóa mềm, bất biến. Scope chỉ có ở INTEGRATION, site key chỉ
 * có ở PUBLIC_SITE.
 */
public class Application {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_RATE_LIMIT = 1_000_000;

    private final UUID id;
    private final UUID tenantId;
    private String name;
    private final ApplicationKind kind;
    private ApplicationStatus status;
    private String siteKey;
    private Integer rateLimitPerMinute;
    private final EnumSet<ApplicationScope> scopes;
    private final Instant createdAt;
    private final UUID createdBy;
    private Instant updatedAt;
    private UUID updatedBy;
    private Instant deletedAt;
    private long version;

    private Application(UUID id, UUID tenantId, String name, ApplicationKind kind, ApplicationStatus status,
                        String siteKey, Integer rateLimitPerMinute, Collection<ApplicationScope> scopes,
                        Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy, Instant deletedAt,
                        long version) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.kind = kind;
        this.status = status;
        this.siteKey = siteKey;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.scopes = scopes.isEmpty() ? EnumSet.noneOf(ApplicationScope.class) : EnumSet.copyOf(scopes);
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
        this.deletedAt = deletedAt;
        this.version = version;
    }

    /** @param siteKey bắt buộc với PUBLIC_SITE, phải null với INTEGRATION */
    public static Application create(UUID tenantId, String name, ApplicationKind kind, Integer rateLimitPerMinute,
                                     String siteKey, UUID actor, Instant at) {
        if (tenantId == null) throw invalid("tenantId không được null");
        if (kind == null) throw invalid("Loại ứng dụng không hợp lệ");
        if (kind == ApplicationKind.PUBLIC_SITE && !SiteKey.isValid(siteKey)) throw invalid("Site key không hợp lệ");
        if (kind == ApplicationKind.INTEGRATION && siteKey != null) throw invalid("Ứng dụng tích hợp không có site key");
        return new Application(UUID.randomUUID(), tenantId, requireName(name), kind, ApplicationStatus.ACTIVE, siteKey,
                requireRateLimit(rateLimitPerMinute), EnumSet.noneOf(ApplicationScope.class), at, actor, at, actor,
                null, 0);
    }

    public static Application restore(UUID id, UUID tenantId, String name, ApplicationKind kind,
                                      ApplicationStatus status, String siteKey, Integer rateLimitPerMinute,
                                      Collection<ApplicationScope> scopes, Instant createdAt, UUID createdBy,
                                      Instant updatedAt, UUID updatedBy, Instant deletedAt, long version) {
        return new Application(id, tenantId, name, kind, status, siteKey, rateLimitPerMinute, scopes, createdAt,
                createdBy, updatedAt, updatedBy, deletedAt, version);
    }

    /** Sửa tên và hạn mức (null = mặc định cấu hình). */
    public void update(String newName, Integer newRateLimit, UUID actor, Instant at) {
        requireActive();
        this.name = requireName(newName);
        this.rateLimitPerMinute = requireRateLimit(newRateLimit);
        touch(actor, at);
    }

    public void suspend(UUID actor, Instant at) {
        if (status != ApplicationStatus.ACTIVE) {
            throw new ApplicationException(Reason.APPLICATION_NOT_ACTIVE, "Ứng dụng không ở trạng thái ACTIVE");
        }
        status = ApplicationStatus.SUSPENDED;
        touch(actor, at);
    }

    public void reactivate(UUID actor, Instant at) {
        if (status != ApplicationStatus.SUSPENDED) {
            throw new ApplicationException(Reason.APPLICATION_NOT_SUSPENDED, "Ứng dụng không bị tạm ngưng");
        }
        status = ApplicationStatus.ACTIVE;
        touch(actor, at);
    }

    /** Xóa mềm (từ ACTIVE hoặc SUSPENDED). Người gọi thu hồi credential và domain trong cùng transaction. */
    public void delete(UUID actor, Instant at) {
        if (status == ApplicationStatus.DELETED) {
            throw new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng");
        }
        status = ApplicationStatus.DELETED;
        deletedAt = at;
        touch(actor, at);
    }

    /**
     * Thay toàn bộ scope được cấp.
     *
     * @throws ApplicationException KIND_MISMATCH (không phải INTEGRATION), APPLICATION_NOT_ACTIVE, SCOPE_NOT_GRANTABLE
     */
    public void replaceScopes(Collection<ApplicationScope> granted, UUID actor, Instant at) {
        if (kind != ApplicationKind.INTEGRATION) {
            throw new ApplicationException(Reason.KIND_MISMATCH, "Chỉ ứng dụng tích hợp mới có scope");
        }
        requireActive();
        granted.forEach(ApplicationScope::requireGrantable);
        scopes.clear();
        scopes.addAll(granted);
        touch(actor, at);
    }

    /** Thay site key (key cũ hết hiệu lực ngay ở identity; cache BFF tối đa 60 giây, pha P2). */
    public void rotateSiteKey(String newSiteKey, UUID actor, Instant at) {
        if (kind != ApplicationKind.PUBLIC_SITE) {
            throw new ApplicationException(Reason.KIND_MISMATCH, "Chỉ ứng dụng website mới có site key");
        }
        requireActive();
        if (!SiteKey.isValid(newSiteKey)) throw invalid("Site key không hợp lệ");
        this.siteKey = newSiteKey;
        touch(actor, at);
    }

    /** Credential chỉ thuộc về Application INTEGRATION còn ACTIVE. */
    public void requireCanIssueCredential() {
        if (kind != ApplicationKind.INTEGRATION) {
            throw new ApplicationException(Reason.KIND_MISMATCH, "Chỉ ứng dụng tích hợp mới có credential");
        }
        requireActive();
    }

    /** Domain allowlist chỉ thuộc về Application PUBLIC_SITE. */
    public void requireWebsite() {
        if (kind != ApplicationKind.PUBLIC_SITE) {
            throw new ApplicationException(Reason.KIND_MISMATCH, "Chỉ ứng dụng website mới có domain allowlist");
        }
    }

    public void requireActive() {
        if (status != ApplicationStatus.ACTIVE) {
            throw new ApplicationException(Reason.APPLICATION_NOT_ACTIVE, "Ứng dụng không ở trạng thái ACTIVE");
        }
    }

    private void touch(UUID actor, Instant at) {
        this.updatedAt = at;
        this.updatedBy = actor;
        this.version++;
    }

    private static String requireName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > MAX_NAME_LENGTH) {
            throw invalid("Tên ứng dụng phải từ 1 đến " + MAX_NAME_LENGTH + " ký tự");
        }
        return value;
    }

    private static Integer requireRateLimit(Integer value) {
        if (value != null && (value < 1 || value > MAX_RATE_LIMIT)) {
            throw invalid("Hạn mức yêu cầu mỗi phút phải từ 1 đến " + MAX_RATE_LIMIT);
        }
        return value;
    }

    private static ApplicationException invalid(String message) {
        return new ApplicationException(Reason.INVALID_APPLICATION, message);
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String name() { return name; }
    public ApplicationKind kind() { return kind; }
    public ApplicationStatus status() { return status; }
    public String siteKey() { return siteKey; }
    public Integer rateLimitPerMinute() { return rateLimitPerMinute; }
    public Set<ApplicationScope> scopes() { return Set.copyOf(scopes); }
    public Instant createdAt() { return createdAt; }
    public UUID createdBy() { return createdBy; }
    public Instant updatedAt() { return updatedAt; }
    public UUID updatedBy() { return updatedBy; }
    public Instant deletedAt() { return deletedAt; }
    public long version() { return version; }
}
