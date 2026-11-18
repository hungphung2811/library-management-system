package lms.identity.user.infrastructure.persistence;

import lms.identity.user.domain.Gender;
import lms.identity.user.domain.UserPersonalInfo;
import lms.identity.user.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private String address;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "employee_code")
    private String employeeCode;

    protected UserJpaEntity() {
    }

    UserJpaEntity(UUID id, String username, String fullName, String email, UserStatus status,
                  Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.username = username;
        this.fullName = fullName;
        this.email = email;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    UserJpaEntity(UUID id, String username, String fullName, String email, UserStatus status,
                  Instant createdAt, Instant updatedAt, UserPersonalInfo info) {
        this(id, username, fullName, email, status, createdAt, updatedAt);
        this.phone = info.phone();
        this.dateOfBirth = info.dateOfBirth();
        this.gender = info.gender();
        this.address = info.address();
        this.avatarUrl = info.avatarUrl();
        this.jobTitle = info.jobTitle();
        this.employeeCode = info.employeeCode();
    }

    UserPersonalInfo personalInfo() {
        return new UserPersonalInfo(phone, dateOfBirth, gender, address, avatarUrl, jobTitle, employeeCode);
    }

    UUID getId() { return id; }
    String getUsername() { return username; }
    String getFullName() { return fullName; }
    String getEmail() { return email; }
    UserStatus getStatus() { return status; }
    Instant getCreatedAt() { return createdAt; }
}
