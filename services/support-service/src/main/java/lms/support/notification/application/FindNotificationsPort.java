package lms.support.notification.application;

import java.util.List;
import java.util.UUID;
import lms.support.notification.domain.Notification;

public interface FindNotificationsPort {

    /** Thông báo của người dùng trong tenant hiện tại, mới nhất trước. */
    List<Notification> findByUser(UUID userId);
}
