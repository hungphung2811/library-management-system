package lms.identity.auth.domain;

import java.time.Instant;

public class AccountLockedException extends RuntimeException {
    public AccountLockedException(Instant until) {
        super("Tài khoản đang bị khóa đến " + until);
    }
}
