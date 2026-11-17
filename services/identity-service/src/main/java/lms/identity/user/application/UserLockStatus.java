package lms.identity.user.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Gắn trạng thái khóa đăng nhập (locked/lockedUntil) vào UserResponse, tra theo lô để không N+1. */
public class UserLockStatus {

    private final AccountCredentialPort credentials;
    private final Clock clock;

    public UserLockStatus(AccountCredentialPort credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
    }

    public UserResponse apply(UserResponse response) {
        return applyAll(List.of(response)).get(0);
    }

    public List<UserResponse> applyAll(List<UserResponse> responses) {
        if (responses.isEmpty()) return responses;
        Map<UUID, Instant> locks = credentials.activeLocks(responses.stream().map(UserResponse::id).toList(),
                clock.instant());
        if (locks.isEmpty()) return responses;
        return responses.stream().map(r -> r.withLock(locks.get(r.id()))).toList();
    }
}
