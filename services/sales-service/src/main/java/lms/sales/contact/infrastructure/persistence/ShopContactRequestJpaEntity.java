package lms.sales.contact.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.sales.contact.domain.ContactStatus;
import lms.sales.contact.domain.ContactTopic;
import lms.sales.contact.domain.ShopContactRequest;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Bảng shop_contact_requests (RLS FORCE, chỉ policy tenant_isolation). Không in dữ liệu cá nhân. */
@Entity
@Table(name = "shop_contact_requests")
class ShopContactRequestJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "customer_user_id")
    private UUID customerUserId;

    @Column(name = "topic", nullable = false, updatable = false)
    private String topic;

    @Column(name = "message")
    private String message;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "consent_at", nullable = false, updatable = false)
    private Instant consentAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "pii_anonymized_at")
    private Instant piiAnonymizedAt;

    protected ShopContactRequestJpaEntity() {
    }

    ShopContactRequestJpaEntity(ShopContactRequest r) {
        this.tenantId = TenantContext.require();
        this.id = r.id();
        this.topic = r.topic().name();
        this.consentAt = r.consentAt();
        this.createdAt = r.createdAt();
        apply(r);
    }

    /** Chép các trường được phép đổi sau khi tạo: trạng thái, mốc đóng, dữ liệu cá nhân (về null khi ẩn danh). */
    void apply(ShopContactRequest r) {
        this.customerUserId = r.customerUserId();
        this.message = r.message();
        this.contactName = r.contactName();
        this.contactEmail = r.contactEmail();
        this.contactPhone = r.contactPhone();
        this.status = r.status().name();
        this.closedAt = r.closedAt();
        this.piiAnonymizedAt = r.piiAnonymizedAt();
    }

    ShopContactRequest toDomain() {
        return ShopContactRequest.restore(id, tenantId, customerUserId, ContactTopic.valueOf(topic), message,
                contactName, contactEmail, contactPhone, consentAt, ContactStatus.valueOf(status), version,
                createdAt, closedAt, piiAnonymizedAt);
    }

    long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "ShopContactRequestJpaEntity[id=" + id + ", ***]";
    }
}
