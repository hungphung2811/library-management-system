package lms.api.support.client;

import lms.api.support.dto.AuditLogView;
import lms.api.support.dto.NotificationView;
import lms.api.support.dto.PageView;
import java.util.List;

/** Cổng sang support-service (API nội bộ). Lỗi 4xx của service được ném dưới dạng RestClientResponseException. */
public interface SupportClient {

    PageView<AuditLogView> searchAuditLogs(String aggregateId, String eventType, int page, int size);

    /** Thông báo của người đang đăng nhập trong tenant của token. */
    List<NotificationView> myNotifications();
}
