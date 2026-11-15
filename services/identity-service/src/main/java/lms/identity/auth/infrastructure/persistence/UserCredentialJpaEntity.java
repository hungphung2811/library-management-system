package lms.identity.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credentials")
class UserCredentialJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "platform_admin", nullable = false)
    private boolean platformAdmin;

    protected UserCredentialJpaEntity() {
    }

    UserCredentialJpaEntity(UUID userId, String username, String passwordHash, UUID departmentId,
                            boolean active, int failedAttempts, Instant lockedUntil,
                            boolean mustChangePassword, boolean platformAdmin) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.departmentId = departmentId;
        this.active = active;
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
        this.mustChangePassword = mustChangePassword;
        this.platformAdmin = platformAdmin;
    }

    UUID getUserId() { return userId; }
    String getUsername() { return username; }
    String getPasswordHash() { return passwordHash; }
    UUID getDepartmentId() { return departmentId; }
    boolean isActive() { return active; }
    int getFailedAttempts() { return failedAttempts; }
    Instant getLockedUntil() { return lockedUntil; }
    boolean isMustChangePassword() { return mustChangePassword; }
    boolean isPlatformAdmin() { return platformAdmin; }
}
