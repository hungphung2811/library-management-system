package lms.identity.user.domain;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(UUID id) {
        super("Không tìm thấy người dùng: " + id);
    }
}
