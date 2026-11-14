package lms.identity.user.domain;

import java.util.UUID;

/** Điều kiện tìm người dùng; keyword khớp một phần tên đăng nhập, họ tên hoặc email (không phân biệt hoa thường). */
public record UserCriteria(String keyword, UUID departmentId, UserStatus status, int page, int size) {
}
