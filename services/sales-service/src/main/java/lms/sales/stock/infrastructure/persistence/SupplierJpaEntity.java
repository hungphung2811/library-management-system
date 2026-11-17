package lms.sales.stock.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "suppliers")
class SupplierJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "address")
    private String address;

    @Column(name = "note")
    private String note;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SupplierJpaEntity() {
    }

    SupplierJpaEntity(UUID id, String name, String phone, String email, String address, String note, boolean active) {
        this.tenantId = TenantContext.require();
        this.id = id;
        this.createdAt = Instant.now();
        apply(name, phone, email, address, note, active);
    }

    void apply(String name, String phone, String email, String address, String note, boolean active) {
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.address = address;
        this.note = note;
        this.active = active;
        this.updatedAt = Instant.now();
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getName() { return name; }
    String getPhone() { return phone; }
    String getEmail() { return email; }
    String getAddress() { return address; }
    String getNote() { return note; }
    boolean isActive() { return active; }
}
