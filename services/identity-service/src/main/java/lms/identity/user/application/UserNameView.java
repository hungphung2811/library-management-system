package lms.identity.user.application;

import java.util.UUID;

/** Kết quả tra theo lô: chỉ id và họ tên, không email/điện thoại/tên đăng nhập. */
public record UserNameView(UUID id, String fullName) {
}
