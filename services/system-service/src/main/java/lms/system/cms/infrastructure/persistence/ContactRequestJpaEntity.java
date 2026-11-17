package lms.system.cms.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.system.cms.domain.BusinessType;
import lms.system.cms.domain.ContactRequest;
import lms.system.cms.domain.ContactStatus;

/**
 * Yêu cầu liên hệ cấp nền tảng (system V3): KHÔNG có tenant_id, không RLS. Cột họ tên, email, điện thoại, lời nhắn là
 * dữ liệu cá nhân (NULL sau khi ẩn danh). Không có {@code toString} để không vô tình in dữ liệu cá nhân.
 */
@Entity
@Table(name = "cms_contact_requests")
class ContactRequestJpaEntity {

    @Id
    private UUID id;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(length = 254)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false, length = 200)
    private String organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 20)
    private BusinessType businessType;

    @Column(length = 2000)
    private String message;

    @Column(name = "consent_at", nullable = false, updatable = false)
    private Instant consentAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContactStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "handled_by")
    private UUID handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    protected ContactRequestJpaEntity() {
    }

    static ContactRequestJpaEntity create(ContactRequest request) {
        ContactRequestJpaEntity e = new ContactRequestJpaEntity();
        e.id = request.id();
        e.organization = request.organization();
        e.businessType = request.businessType();
        e.consentAt = request.consentAt();
        e.createdAt = request.createdAt();
        e.apply(request);
        return e;
    }

    void apply(ContactRequest request) {
        this.fullName = request.fullName();
        this.email = request.email();
        this.phone = request.phone();
        this.message = request.message();
        this.status = request.status();
        this.handledBy = request.handledBy();
        this.handledAt = request.handledAt();
        this.anonymizedAt = request.anonymizedAt();
    }

    ContactRequest toDomain() {
        return new ContactRequest(id, fullName, email, phone, organization, businessType, message, consentAt,
                status, version == null ? 0L : version, createdAt, handledBy, handledAt, anonymizedAt);
    }
}
