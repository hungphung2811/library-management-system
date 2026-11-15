package lms.support.notification.application;

import lms.support.notification.domain.Notification;

public interface SaveNotificationPort {

    /** @return true nếu tạo mới, false nếu eventId đã có trong tenant hiện tại. */
    boolean saveIfAbsent(Notification notification);
}
