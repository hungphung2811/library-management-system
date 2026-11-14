package lms.identity.auth.domain;

import lms.identity.auth.domain.AccountLockedEvent;
import lms.identity.auth.domain.LoginFailedEvent;
import lms.identity.auth.domain.LoginSucceededEvent;
import lms.identity.auth.domain.AccountDisabledException;
import lms.identity.auth.domain.AccountLockedException;
import lms.common.kernel.DomainEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root: thông tin xác thực của một người dùng. Chỉ auth-service giữ password (đã băm),
 * user-service không lưu mật khẩu.
 */
public class UserCredential {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final UUID userId;
    private final String username;
    private final String passwordHash;
    private final UUID departmentId;
    private boolean active;
    private int failedAttempts;
    private Instant lockedUntil;
    private final List<DomainEvent> events = new ArrayList<>();

    private UserCredential(UUID userId, String username, String passwordHash, UUID departmentId,
                           boolean active, int failedAttempts, Instant lockedUntil) {
        if (userId == null) throw new IllegalArgumentException("userId không được null");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("username không được trống");
        if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("passwordHash không được trống");
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.departmentId = departmentId;
        this.active = active;
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
    }

    public static UserCredential register(UUID userId, String username, String passwordHash, UUID departmentId) {
        return new UserCredential(userId, username, passwordHash, departmentId, true, 0, null);
    }

    public static UserCredential restore(UUID userId, String username, String passwordHash, UUID departmentId,
                                         boolean active, int failedAttempts, Instant lockedUntil) {
        return new UserCredential(userId, username, passwordHash, departmentId, active, failedAttempts, lockedUntil);
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** Kiểm tra trước khi so khớp mật khẩu: tài khoản bị khóa hoặc vô hiệu hóa thì không cho thử. */
    public void ensureCanLogin(Instant now) {
        if (!active) throw new AccountDisabledException();
        if (isLocked(now)) throw new AccountLockedException(lockedUntil);
    }

    public void recordFailure(Instant now) {
        failedAttempts++;
        events.add(new LoginFailedEvent(UUID.randomUUID(), userId, username, now));
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plus(LOCK_DURATION);
            failedAttempts = 0;
            events.add(new AccountLockedEvent(UUID.randomUUID(), userId, username, lockedUntil, now));
        }
    }

    public void recordSuccess(Instant now) {
        failedAttempts = 0;
        lockedUntil = null;
        events.add(new LoginSucceededEvent(UUID.randomUUID(), userId, username, now));
    }

    public void deactivate() {
        active = false;
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    public UUID userId() { return userId; }
    public String username() { return username; }
    public String passwordHash() { return passwordHash; }
    public UUID departmentId() { return departmentId; }
    public boolean isActive() { return active; }
    public int failedAttempts() { return failedAttempts; }
    public Instant lockedUntil() { return lockedUntil; }
}
