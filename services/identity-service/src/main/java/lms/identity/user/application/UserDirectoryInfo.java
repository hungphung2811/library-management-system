package lms.identity.user.application;

import java.util.UUID;

/** Thông tin người dùng mà context auth cần khi cấp token. */
public record UserDirectoryInfo(boolean disabled, UUID departmentId) {
}
