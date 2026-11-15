package lms.support.notification.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.support.notification.domain.Notification;

/** Đọc/ghi trạng thái đã đọc của thông báo thuộc chính người dùng trong tenant hiện tại. */
public interface MarkNotificationsPort {

    /** Thông báo của userId; rỗng nếu không tồn tại hoặc của người khác. */
    Optional<Notification> findOwned(UUID id, UUID userId);

    /** Ghi readAt chỉ khi chưa đọc (giữ thời điểm đọc đầu tiên). */
    void markRead(UUID id, UUID userId, Instant at);

    /** Đánh dấu mọi thông báo chưa đọc của userId; trả số dòng đổi. */
    int markAllRead(UUID userId, Instant at);

    long countUnread(UUID userId);
}
