package lms.api.support.client.http;

import lms.api.support.client.SupportClient;
import lms.api.support.dto.AuditLogView;
import lms.api.support.dto.NotificationView;
import lms.api.support.dto.PageView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Gọi API nội bộ /internal/audit-logs của support-service, ánh xạ sang DTO của FE. */
public class HttpSupportClient implements SupportClient {

    /** DTO nội bộ của support-service; tách khỏi DTO cho FE để hai bên đổi độc lập. */
    record InternalAuditLog(UUID id, UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                            Instant recordedAt, String payload) {
    }

    record InternalPage(List<InternalAuditLog> items, long total, int page, int size) {
    }

    private final RestClient client;

    public HttpSupportClient(RestClient client) {
        this.client = client;
    }

    @Override
    public List<NotificationView> myNotifications() {
        List<NotificationView> result = client.get().uri("/internal/notifications/me").retrieve()
                .body(new ParameterizedTypeReference<List<NotificationView>>() { });
        return result == null ? List.of() : result;
    }

    @Override
    public PageView<AuditLogView> searchAuditLogs(String aggregateId, String eventType, int page, int size) {
        InternalPage result = client.get().uri(b -> {
            b.path("/internal/audit-logs").queryParam("page", page).queryParam("size", size);
            if (aggregateId != null && !aggregateId.isBlank()) b.queryParam("aggregateId", aggregateId);
            if (eventType != null && !eventType.isBlank()) b.queryParam("eventType", eventType);
            return b.build();
        }).retrieve().body(InternalPage.class);
        if (result == null) return new PageView<>(List.of(), 0, page, size);
        List<AuditLogView> items = result.items() == null ? List.of() : result.items().stream()
                .map(l -> new AuditLogView(l.id(), l.eventId(), l.eventType(), l.aggregateId(), l.occurredAt(),
                        l.recordedAt(), l.payload())).toList();
        return new PageView<>(items, result.total(), result.page(), result.size());
    }
}
