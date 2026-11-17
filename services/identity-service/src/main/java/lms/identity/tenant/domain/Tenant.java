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
    private UUID organizationId;
    private TenantStatus status;
    private SuspensionCause suspensionCause;
    private UUID suspendedByContractId;
    private final Instant createdAt;

    private Tenant(UUID id, String code, String name, UUID organizationId, TenantStatus status, Instant createdAt,
                   SuspensionCause suspensionCause, UUID suspendedByContractId) {
        if (id == null) throw new InvalidTenantException("id không được null");
        if (code == null || !CODE.matcher(code).matches()) {
            throw new InvalidTenantException("Mã tenant chỉ gồm chữ thường, số và '-', từ 2 đến 50 ký tự");
        }
        requireName(name);
        if (status == null) throw new InvalidTenantException("Trạng thái không được null");
        this.id = id;
        this.code = code;
        this.name = name.trim();
        this.organizationId = organizationId;
        this.status = status;
        this.createdAt = createdAt;
        if (status == TenantStatus.ACTIVE) {
            this.suspensionCause = null;
            this.suspendedByContractId = null;
        } else {
            // dữ liệu cũ không biết nguyên nhân thì coi là tay (an toàn: không bao giờ tự mở)
            this.suspensionCause = suspensionCause != null ? suspensionCause : SuspensionCause.MANUAL;
            this.suspendedByContractId = this.suspensionCause == SuspensionCause.MANUAL ? null : suspendedByContractId;
        }
    }

    public static Tenant create(String code, String name, UUID organizationId) {
        return new Tenant(UUID.randomUUID(), code, name, organizationId, TenantStatus.ACTIVE, Instant.now(), null,
                null);
    }

    /** Tạo với id cho trước (dữ liệu demo cần id tất định để các service khác khớp; ADR-0018). */
    public static Tenant create(UUID id, String code, String name, UUID organizationId) {
        return new Tenant(id, code, name, organizationId, TenantStatus.ACTIVE, Instant.now(), null, null);
    }

    public static Tenant restore(UUID id, String code, String name, UUID organizationId, TenantStatus status,
                                 Instant createdAt) {
        return new Tenant(id, code, name, organizationId, status, createdAt, null, null);
    }

    public static Tenant restore(UUID id, String code, String name, UUID organizationId, TenantStatus status,
                                 Instant createdAt, SuspensionCause suspensionCause, UUID suspendedByContractId) {
        return new Tenant(id, code, name, organizationId, status, createdAt, suspensionCause, suspendedByContractId);
    }

    /** Đổi tên; mã ({@code code}) bất biến sau khi tạo để không vỡ tham chiếu. */
    public void rename(String newName) {
        requireName(newName);
        this.name = newName.trim();
    }

    /** Gán hoặc đổi trường (null = không thuộc trường nào). */
    public void moveToOrganization(UUID newOrganizationId) {
        this.organizationId = newOrganizationId;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new InvalidTenantException("Tên tenant không được để trống");
        if (name.trim().length() > 255) throw new InvalidTenantException("Tên tenant tối đa 255 ký tự");
    }

    /** Tạm ngưng tay (platform admin): luôn ghi {@code MANUAL}, kể cả khi đang bị tạm ngưng do hợp đồng (leo thang). */
    public void suspend() {
        this.status = TenantStatus.SUSPENDED;
        this.suspensionCause = SuspensionCause.MANUAL;
        this.suspendedByContractId = null;
    }

    /**
     * Tạm ngưng do hợp đồng (hết hạn hoặc hủy). Chỉ có tác dụng khi tenant đang ACTIVE: đang SUSPENDED thì giữ nguyên
     * nguyên nhân hiện có.
     *
     * @return true nếu đã đổi từ ACTIVE sang SUSPENDED
     */
    public boolean suspendByContract(SuspensionCause cause, UUID contractId) {
        if (cause == null || cause == SuspensionCause.MANUAL) {
            throw new InvalidTenantException("Nguyên nhân tạm ngưng do hợp đồng phải là CONTRACT_*");
        }
        if (status != TenantStatus.ACTIVE) return false;
        this.status = TenantStatus.SUSPENDED;
        this.suspensionCause = cause;
        this.suspendedByContractId = contractId;
        return true;
    }

    /** Kích hoạt tay: xóa nguyên nhân tạm ngưng. */
    public void activate() {
        this.status = TenantStatus.ACTIVE;
        this.suspensionCause = null;
        this.suspendedByContractId = null;
    }

    /**
     * Tự kích hoạt lại chỉ khi đang SUSPENDED đúng nguyên nhân {@code cause} (ADR-0012: chỉ CONTRACT_EXPIRED).
     *
     * @return true nếu đã mở
     */
    public boolean activateIfSuspendedBy(SuspensionCause cause) {
        if (status != TenantStatus.SUSPENDED || suspensionCause != cause) return false;
        activate();
        return true;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public UUID organizationId() { return organizationId; }
    public TenantStatus status() { return status; }
    /** Null khi ACTIVE. */
    public SuspensionCause suspensionCause() { return suspensionCause; }
    public UUID suspendedByContractId() { return suspendedByContractId; }
    public Instant createdAt() { return createdAt; }
}
