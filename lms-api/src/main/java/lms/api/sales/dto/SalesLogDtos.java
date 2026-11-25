package lms.api.sales.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO log vận hành sales (ADR-0029, lưu DB), hợp đồng khớp /internal/sales/logs/** của sales-service. Trường thời điểm là
 * ISO-8601 UTC. Không có thân request/token: chỉ trường đã che sẵn.
 */
public final class SalesLogDtos {

    private SalesLogDtos() {
    }

    /**
     * Bộ lọc dùng chung hai tab. {@code channel} (OUTBOUND, INBOUND) chỉ có nghĩa ở tab tích hợp; {@code from} bao gồm,
     * {@code to} loại trừ, tối đa 31 ngày; {@code size} tối đa 100; {@code tenantId} chỉ platform admin dùng.
     */
    public record LogQuery(String channel, String provider, String outcome, String level, Boolean anomalyOnly,
                           Instant from, Instant to, UUID tenantId, Integer page, Integer size) {
    }

    /** Một dòng log. {@code channel}: INTEGRATION_OUTBOUND, INTEGRATION_INBOUND, SYSTEM; {@code level}: INFO, WARN, ERROR... */
    public record LogEventView(UUID id, Instant occurredAt, String channel, String provider, String event, String level,
                               String op, String outcome, Integer httpStatus, Long durationMs, String errorKind,
                               String errorClass, String anomalyType, String correlationId, UUID intentId,
                               String reference, UUID tenantId) {
    }

    public record LogPage(List<LogEventView> items, long total, int page, int size) {
    }
}
