package lms.identity.auth.application;

import java.util.Optional;
import java.util.UUID;

/** Cổng sang context user: trạng thái hiện tại của người dùng khi cấp token (phòng ban lấy từ membership của tenant). */
public interface UserDirectoryPort {

    /** Rỗng khi không có bản ghi user (ví dụ tài khoản quản trị khởi tạo): coi như đang hoạt động. */
    Optional<UserInfo> find(UUID userId);

    record UserInfo(boolean disabled) {
    }
}
