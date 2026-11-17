package lms.identity.user.application;

import lms.identity.user.domain.UserStatus;
import java.util.UUID;

/** Bộ lọc xuất người dùng, giống bộ lọc tìm kiếm (keyword khớp một phần tên đăng nhập/họ tên/email). */
public record UserExportQuery(String keyword, UUID departmentId, UserStatus status) {
}
