package lms.identity.user.application;

/** Thông tin người dùng mà context auth cần khi cấp token. */
public record UserDirectoryInfo(boolean disabled) {
}
