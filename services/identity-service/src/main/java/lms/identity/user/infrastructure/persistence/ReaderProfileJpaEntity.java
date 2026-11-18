package lms.identity.user.infrastructure.persistence;

import lms.identity.user.domain.Gender;
import lms.identity.user.domain.ReaderPersonalInfo;
import lms.identity.user.domain.ReaderStatus;
import lms.identity.user.domain.ReaderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import lms.tenant.TenantContext;

/**
 * Hồ sơ độc giả của một người dùng trong một tenant. Khóa chính ở DB là (tenant_id, user_id); ở JPA chỉ cần user_id vì
 * mỗi phiên Hibernate gắn một tenant (@TenantId lọc và điền tenant_id, RLS lọc thêm). card_no unique theo tenant.
 */
@Entity
@Table(name = "reader_profiles")
class ReaderProfileJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "card_no", nullable = false)
    private String cardNo;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "reader_type", nullable = false)
    private ReaderType readerType;

    @Column(name = "card_expiry_date", nullable = false)
    private LocalDate cardExpiryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReaderStatus status;

    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private String address;

    private String faculty;

    @Column(name = "class_name")
    private String className;

    @Column(name = "member_code")
    private String memberCode;

    @Column(name = "card_issued_date")
    private LocalDate cardIssuedDate;

    protected ReaderProfileJpaEntity() {
    }

    ReaderProfileJpaEntity(UUID userId, String cardNo, String fullName, String email, ReaderType readerType,
                           LocalDate cardExpiryDate, ReaderStatus status) {
        // Điền sẵn tenant của phiên: merge (save entity mới dựng) không ghi đè tenant_id thành null, insert khớp
        // tenant phiên; không có tenant thì ném TenantRequiredException (fail-closed). Cột updatable=false.
        this.tenantId = TenantContext.require();
        this.userId = userId;
        this.cardNo = cardNo;
        this.fullName = fullName;
        this.email = email;
        this.readerType = readerType;
        this.cardExpiryDate = cardExpiryDate;
        this.status = status;
    }

    ReaderProfileJpaEntity(UUID userId, String cardNo, String fullName, String email, ReaderType readerType,
                           LocalDate cardExpiryDate, ReaderStatus status, ReaderPersonalInfo info) {
        this(userId, cardNo, fullName, email, readerType, cardExpiryDate, status);
        this.phone = info.phone();
        this.dateOfBirth = info.dateOfBirth();
        this.gender = info.gender();
        this.address = info.address();
        this.faculty = info.faculty();
        this.className = info.className();
        this.memberCode = info.memberCode();
        this.cardIssuedDate = info.cardIssuedDate();
    }

    ReaderPersonalInfo personalInfo() {
        return new ReaderPersonalInfo(phone, dateOfBirth, gender, address, faculty, className, memberCode,
                cardIssuedDate);
    }

    UUID getUserId() { return userId; }
    String getCardNo() { return cardNo; }
    String getFullName() { return fullName; }
    String getEmail() { return email; }
    ReaderType getReaderType() { return readerType; }
    LocalDate getCardExpiryDate() { return cardExpiryDate; }
    ReaderStatus getStatus() { return status; }
}
