package lms.identity.auth.domain;

import java.time.Instant;
import java.util.UUID;

/** Liên kết giữa một tài khoản nội bộ và một danh tính ở nhà cung cấp ngoài, khóa là (provider, subject). */
public class ExternalLogin {

    private final UUID id;
    private final UUID userId;
    private final String provider;
    private final String subject;
    private String email;
    private final Instant linkedAt;
    private Instant lastUsedAt;

    private ExternalLogin(UUID id, UUID userId, String provider, String subject, String email, Instant linkedAt,
                          Instant lastUsedAt) {
        if (id == null || userId == null) throw new IllegalArgumentException("id/userId không được null");
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider không được trống");
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject không được trống");
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.subject = subject;
        this.email = email;
        this.linkedAt = linkedAt;
        this.lastUsedAt = lastUsedAt;
    }

    public static ExternalLogin link(UUID userId, String provider, String subject, String email, Instant now) {
        return new ExternalLogin(UUID.randomUUID(), userId, provider, subject, email, now, now);
    }

    public static ExternalLogin restore(UUID id, UUID userId, String provider, String subject, String email,
                                        Instant linkedAt, Instant lastUsedAt) {
        return new ExternalLogin(id, userId, provider, subject, email, linkedAt, lastUsedAt);
    }

    /** Ghi nhận một lần đăng nhập; email ở IdP có thể đổi nên cập nhật theo lần gần nhất. */
    public void markUsed(Instant now, String latestEmail) {
        this.lastUsedAt = now;
        if (latestEmail != null && !latestEmail.isBlank()) {
            this.email = latestEmail;
        }
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public String provider() { return provider; }
    public String subject() { return subject; }
    public String email() { return email; }
    public Instant linkedAt() { return linkedAt; }
    public Instant lastUsedAt() { return lastUsedAt; }
}
