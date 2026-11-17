package lms.system.menu.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.system.menu.domain.MenuItem;
import org.hibernate.annotations.TenantId;

/**
 * Mục menu của một tenant: tenant_id do Hibernate điền/lọc theo TenantContext (@TenantId), RLS lọc thêm ở DB
 * (system V1). {@code version} là khóa lạc quan (@Version). {@code parent_id} chỉ là cột thường: khóa ngoại kép
 * {@code (parent_id, tenant_id)} do DB giữ. Không có SQL native.
 */
@Entity
@Table(name = "menus")
class MenuJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false, updatable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(length = 500)
    private String path;

    @Column(length = 100)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "permission_key", length = 255)
    private String permissionKey;

    @Column(length = 20)
    private String module;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MenuJpaEntity() {
    }

    /** Dựng bản ghi mới từ domain (version do Hibernate đặt khi persist). */
    static MenuJpaEntity create(MenuItem item) {
        MenuJpaEntity e = new MenuJpaEntity();
        e.id = item.id();
        e.tenantId = item.tenantId();
        e.code = item.code();
        e.createdAt = item.createdAt();
        e.apply(item);
        return e;
    }

    /** Ghi phần có thể sửa; {@code code}, {@code tenant_id}, {@code created_at} và {@code version} không đổi qua đây. */
    void apply(MenuItem item) {
        this.parentId = item.parentId();
        this.label = item.label();
        this.path = item.path();
        this.icon = item.icon();
        this.sortOrder = item.sortOrder();
        this.enabled = item.enabled();
        this.permissionKey = item.permissionKey();
        this.module = item.module();
        this.updatedAt = item.updatedAt();
    }

    MenuItem toDomain() {
        return new MenuItem(id, tenantId, parentId, code, label, path, icon, sortOrder, enabled, permissionKey,
                version == null ? 0L : version, createdAt, updatedAt, module);
    }

    UUID getId() { return id; }
}
