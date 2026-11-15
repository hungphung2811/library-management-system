package lms.identity.user.domain;

import java.util.UUID;

/**
 * Điều kiện tìm người dùng trong một tenant (chỉ người có membership ở tenantId); keyword khớp một phần tên đăng nhập,
 * họ tên hoặc email (không phân biệt hoa thường); departmentId là phòng ban trong tenant đó.
 */
public record UserCriteria(UUID tenantId, String keyword, UUID departmentId, UserStatus status, int page, int size) {

    public UserCriteria {
        if (tenantId == null) throw new InvalidUserException("tenantId không được null");
    }
}
