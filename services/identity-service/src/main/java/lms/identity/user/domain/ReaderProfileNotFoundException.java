package lms.identity.user.domain;

import java.util.UUID;

public class ReaderProfileNotFoundException extends RuntimeException {
    public ReaderProfileNotFoundException(UUID userId) {
        super("Chưa có hồ sơ độc giả cho người dùng: " + userId);
    }
}
