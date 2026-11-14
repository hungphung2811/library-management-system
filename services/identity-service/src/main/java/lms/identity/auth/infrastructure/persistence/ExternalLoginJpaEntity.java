package lms.identity.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_logins")
class ExternalLoginJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String subject;

    private String email;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ExternalLoginJpaEntity() {
    }

    ExternalLoginJpaEntity(UUID id, UUID userId, String provider, String subject, String email, Instant linkedAt,
                           Instant lastUsedAt) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.subject = subject;
        this.email = email;
        this.linkedAt = linkedAt;
        this.lastUsedAt = lastUsedAt;
    }

    UUID getId() { return id; }
    UUID getUserId() { return userId; }
    String getProvider() { return provider; }
    String getSubject() { return subject; }
    String getEmail() { return email; }
    Instant getLinkedAt() { return linkedAt; }
    Instant getLastUsedAt() { return lastUsedAt; }
}
