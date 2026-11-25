package lms.sales.oplog.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Một dòng log vận hành đã lưu (ADR-0029): chỉ các trường đã che sẵn của {@code IntegrationLog}, không bao giờ có thân
 * request, token hay số điện thoại. {@code extra} chỉ chứa khóa trong danh sách trắng của bộ phân tích dòng log.
 */
public record OperationalLogEvent(UUID id, UUID tenantId, Instant occurredAt, String channel, String provider,
                                  String event, String level, String op, String outcome, Integer httpStatus,
                                  Long durationMs, String errorKind, String errorClass, String anomalyType,
                                  String correlationId, UUID intentId, String reference, Map<String, String> extra) {

    public static final String OUTBOUND = "INTEGRATION_OUTBOUND";
    public static final String INBOUND = "INTEGRATION_INBOUND";
    public static final String SYSTEM = "SYSTEM";

    public OperationalLogEvent {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
