package lms.support.notification.application;

import java.util.Map;
import java.util.UUID;
import lms.support.notification.domain.NotificationType;

/**
 * Yêu cầu thông báo do consumer ánh xạ từ một sự kiện. Đúng MỘT trong recipientUserId (có tài khoản) hoặc
 * directEmail (khách lẻ, từ topic notification.direct). params chỉ chứa tham số của loại (không dữ liệu cá nhân).
 * inAppMessage/inAppEventId: thông báo trong ứng dụng (chỉ với recipientUserId); null thì dùng tiêu đề mẫu mặc định
 * và eventId dẫn xuất.
 */
public record NotificationTrigger(UUID eventId, String eventType, NotificationType type, UUID recipientUserId,
                                  String directEmail, Map<String, String> params, String inAppMessage,
                                  UUID inAppEventId) {

    public NotificationTrigger {
        if (eventId == null) throw new IllegalArgumentException("eventId không được null");
        if (type == null) throw new IllegalArgumentException("type không được null");
        if ((recipientUserId == null) == (directEmail == null || directEmail.isBlank())) {
            throw new IllegalArgumentException("Cần đúng một trong recipientUserId hoặc directEmail");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public static NotificationTrigger forUser(UUID eventId, String eventType, NotificationType type, UUID userId,
                                              Map<String, String> params) {
        return new NotificationTrigger(eventId, eventType, type, userId, null, params, null, null);
    }

    public static NotificationTrigger forAddress(UUID eventId, String eventType, NotificationType type, String email,
                                                 Map<String, String> params) {
        return new NotificationTrigger(eventId, eventType, type, null, email, params, null, null);
    }

    public NotificationTrigger withInApp(UUID inAppEventId, String message) {
        return new NotificationTrigger(eventId, eventType, type, recipientUserId, directEmail, params, message,
                inAppEventId);
    }

    @Override
    public String toString() {
        return "NotificationTrigger[eventId=" + eventId + ", type=" + type + "]";
    }
}
