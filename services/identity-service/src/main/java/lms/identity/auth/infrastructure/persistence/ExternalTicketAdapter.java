package lms.identity.auth.infrastructure.persistence;

import lms.identity.auth.application.ExternalTicketPort;
import lms.identity.auth.infrastructure.security.Pkce;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Mã dùng một lần sống 60 giây, chỉ lưu băm SHA-256. */
@Repository
class ExternalTicketAdapter implements ExternalTicketPort {

    static final Duration TTL = Duration.ofSeconds(60);

    private final ExternalTicketJpaRepository jpa;

    ExternalTicketAdapter(ExternalTicketJpaRepository jpa) {
        this.jpa = jpa;
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String issue(UUID userId, Instant now) {
        String raw = Pkce.randomToken(32);
        jpa.save(new ExternalTicketJpaEntity(hash(raw), userId, now.plus(TTL)));
        return raw;
    }

    @Override
    @Transactional
    public Optional<UUID> redeem(String rawCode, Instant now) {
        if (rawCode == null || rawCode.isBlank()) return Optional.empty();
        String h = hash(rawCode);
        if (jpa.markUsed(h, now) != 1) return Optional.empty();
        return jpa.findById(h).map(ExternalTicketJpaEntity::getUserId);
    }
}
