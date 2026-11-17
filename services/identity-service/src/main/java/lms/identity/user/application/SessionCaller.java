package lms.identity.user.application;

import java.util.UUID;

/**
 * Người gọi API quản lý phiên của người khác. admin: claim {@code admin=true} (role quản trị của tenant);
 * platformAdmin: claim {@code platformAdmin=true} (người vận hành nền tảng).
 */
public record SessionCaller(UUID userId, boolean admin, boolean platformAdmin) {
}
