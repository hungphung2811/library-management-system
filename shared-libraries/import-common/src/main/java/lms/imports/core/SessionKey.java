package lms.imports.core;

import java.util.UUID;

/** Khóa phiên: tenant + người dùng + importId. Người khác/tenant khác không bao giờ dựng được cùng khóa. */
public record SessionKey(UUID tenantId, String userId, UUID importId) {
}
