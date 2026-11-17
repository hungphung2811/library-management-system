package lms.identity.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Token đặt lại mật khẩu (quên mật khẩu): dùng một lần, có hạn. Chỉ giữ bản băm; token thô chỉ tồn tại trong event gửi
 * cho kênh thông báo và trong tay người dùng.
 */
public class PasswordResetToken {

    private final UUID id;
    private final UUID userId;
    private final String tokenHash;
    private final Instant expiresAt;
    private final Instant createdAt;
    private Instant usedAt;

    private PasswordResetToken(UUID id, UUID userId, String tokenHash, Instant expiresAt, Instant createdAt,
                               Instant usedAt) {
        if (id == null || userId == null) throw new IllegalArgumentException("id và userId không được null");
        if (tokenHash == null || tokenHash.isBlank()) throw new IllegalArgumentException("tokenHash không được trống");
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.usedAt = usedAt;
    }

    public static PasswordResetToken issue(UUID userId, String tokenHash, Instant now, Duration ttl) {
        return new PasswordResetToken(UUID.randomUUID(), userId, tokenHash, now.plus(ttl), now, null);
    }

    public static PasswordResetToken restore(UUID id, UUID userId, String tokenHash, Instant expiresAt,
                                             Instant createdAt, Instant usedAt) {
        return new PasswordResetToken(id, userId, tokenHash, expiresAt, createdAt, usedAt);
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    /** Ném InvalidPasswordResetTokenException (thông điệp chung) nếu đã dùng hoặc hết hạn. */
    public void ensureUsable(Instant now) {
        if (!isUsable(now)) throw new InvalidPasswordResetTokenException();
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public String tokenHash() { return tokenHash; }
    public Instant expiresAt() { return expiresAt; }
    public Instant createdAt() { return createdAt; }
    public Instant usedAt() { return usedAt; }
}
