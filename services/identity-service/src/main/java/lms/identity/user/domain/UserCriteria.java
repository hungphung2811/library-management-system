package lms.identity.user.domain;

import java.util.UUID;

/**
 * Điều kiện tìm người dùng: tenantId có giá trị thì chỉ người có membership ở tenant đó; tenantId null nghĩa là mọi
 * tenant (phạm vi đọc của platform admin, ADR-0008), khi đó mỗi kết quả là một cặp (người dùng, tenant) và người không
 * có membership nào không xuất hiện. keyword khớp một phần tên đăng nhập, họ tên hoặc email (không phân biệt hoa
 * thường); departmentId là phòng ban trong tenant của dòng membership.
 */
public record UserCriteria(UUID tenantId, String keyword, UUID departmentId, UserStatus status, int page, int size) {
}
