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
    private String passwordHash;
    private final UUID departmentId;
    private boolean active;
    private int failedAttempts;
    private Instant lockedUntil;
    private boolean mustChangePassword;
    private final List<DomainEvent> events = new ArrayList<>();

    private UserCredential(UUID userId, String username, String passwordHash, UUID departmentId,
                           boolean active, int failedAttempts, Instant lockedUntil, boolean mustChangePassword) {
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
        this.mustChangePassword = mustChangePassword;
    }

    public static UserCredential register(UUID userId, String username, String passwordHash, UUID departmentId) {
        return register(userId, username, passwordHash, departmentId, false);
    }

    /** mustChangePassword: tài khoản cấp sẵn mật khẩu tạm, người dùng phải đổi trước khi dùng các chức năng khác. */
    public static UserCredential register(UUID userId, String username, String passwordHash, UUID departmentId,
                                          boolean mustChangePassword) {
        return new UserCredential(userId, username, passwordHash, departmentId, true, 0, null, mustChangePassword);
    }

    public static UserCredential restore(UUID userId, String username, String passwordHash, UUID departmentId,
                                         boolean active, int failedAttempts, Instant lockedUntil) {
        return restore(userId, username, passwordHash, departmentId, active, failedAttempts, lockedUntil, false);
    }

    public static UserCredential restore(UUID userId, String username, String passwordHash, UUID departmentId,
                                         boolean active, int failedAttempts, Instant lockedUntil,
                                         boolean mustChangePassword) {
        return new UserCredential(userId, username, passwordHash, departmentId, active, failedAttempts, lockedUntil,
                mustChangePassword);
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

    /** UC01 - đổi mật khẩu: đặt mật khẩu mới (đã băm), bỏ cờ bắt buộc đổi và xóa trạng thái đăng nhập sai/khóa. */
    public void changePassword(String newPasswordHash) {
        if (newPasswordHash == null || newPasswordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash không được trống");
        }
        this.passwordHash = newPasswordHash;
        this.mustChangePassword = false;
        this.failedAttempts = 0;
        this.lockedUntil = null;
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
    public boolean mustChangePassword() { return mustChangePassword; }
}
