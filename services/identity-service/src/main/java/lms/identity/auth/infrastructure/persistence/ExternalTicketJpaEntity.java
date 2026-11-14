package lms.identity.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_sign_in_tickets")
class ExternalTicketJpaEntity {

    @Id
    @Column(name = "code_hash")
    private String codeHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used;

    protected ExternalTicketJpaEntity() {
    }

    ExternalTicketJpaEntity(String codeHash, UUID userId, Instant expiresAt) {
        this.codeHash = codeHash;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.used = false;
    }

    UUID getUserId() { return userId; }
}
