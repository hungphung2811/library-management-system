package lms.support.notification.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Tra email và tên hiển thị người nhận theo lô ở identity (ADR-0030 D5): sự kiện chỉ mang userId, địa chỉ tra lúc gửi.
 * Người ngoài tenant hoặc không có membership ACTIVE không có trong kết quả.
 */
public interface RecipientDirectoryPort {

    /** @throws RecipientDirectoryException khi identity không trả lời hoặc từ chối (lỗi tạm thời, thử lại sau) */
    Lookup lookup(UUID tenantId, Collection<UUID> userIds);

    record Lookup(String tenantDisplayName, Map<UUID, Recipient> recipients) {
        public Lookup {
            recipients = recipients == null ? Map.of() : Map.copyOf(recipients);
        }
    }

    record Recipient(UUID userId, String email, String displayName, boolean active) {
        @Override
        public String toString() {
            return "Recipient[userId=" + userId + "]";
        }
    }

    class RecipientDirectoryException extends RuntimeException {
        public RecipientDirectoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
