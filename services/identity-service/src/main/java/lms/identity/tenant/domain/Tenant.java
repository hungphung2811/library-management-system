package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** Một thư viện (tenant). Mã là định danh nghiệp vụ, bất biến sau khi tạo. */
public class Tenant {

    private static final Pattern CODE = Pattern.compile("^[a-z0-9-]{2,50}$");

    private final UUID id;
    private final String code;
    private String name;
    private final UUID organizationId;
    private TenantStatus status;
    private final Instant createdAt;

    private Tenant(UUID id, String code, String name, UUID organizationId, TenantStatus status, Instant createdAt) {
        if (id == null) throw new InvalidTenantException("id không được null");
        if (code == null || !CODE.matcher(code).matches()) {
            throw new InvalidTenantException("Mã tenant chỉ gồm chữ thường, số và '-', từ 2 đến 50 ký tự");
        }
        if (name == null || name.isBlank()) throw new InvalidTenantException("Tên tenant không được để trống");
        if (name.trim().length() > 255) throw new InvalidTenantException("Tên tenant tối đa 255 ký tự");
        if (status == null) throw new InvalidTenantException("Trạng thái không được null");
        this.id = id;
        this.code = code;
        this.name = name.trim();
        this.organizationId = organizationId;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static Tenant create(String code, String name, UUID organizationId) {
        return new Tenant(UUID.randomUUID(), code, name, organizationId, TenantStatus.ACTIVE, Instant.now());
    }

    public static Tenant restore(UUID id, String code, String name, UUID organizationId, TenantStatus status,
                                 Instant createdAt) {
        return new Tenant(id, code, name, organizationId, status, createdAt);
    }

    public void suspend() {
        this.status = TenantStatus.SUSPENDED;
    }

    public void activate() {
        this.status = TenantStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public UUID organizationId() { return organizationId; }
    public TenantStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
}
