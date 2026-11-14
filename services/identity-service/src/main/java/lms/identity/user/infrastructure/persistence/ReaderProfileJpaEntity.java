package lms.identity.user.infrastructure.persistence;

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

@Entity
@Table(name = "reader_profiles")
class ReaderProfileJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "card_no", nullable = false, unique = true)
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

    protected ReaderProfileJpaEntity() {
    }

    ReaderProfileJpaEntity(UUID userId, String cardNo, String fullName, String email, ReaderType readerType,
                           LocalDate cardExpiryDate, ReaderStatus status) {
        this.userId = userId;
        this.cardNo = cardNo;
        this.fullName = fullName;
        this.email = email;
        this.readerType = readerType;
        this.cardExpiryDate = cardExpiryDate;
        this.status = status;
    }

    UUID getUserId() { return userId; }
    String getCardNo() { return cardNo; }
    String getFullName() { return fullName; }
    String getEmail() { return email; }
    ReaderType getReaderType() { return readerType; }
    LocalDate getCardExpiryDate() { return cardExpiryDate; }
    ReaderStatus getStatus() { return status; }
}
