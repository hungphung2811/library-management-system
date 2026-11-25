package lms.sales.oplog.infrastructure.logging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lms.sales.oplog.domain.OperationalLogEvent;

/**
 * Phân tích dòng {@code key=value} do {@code IntegrationLog} sinh (ADR-0029 D3) thành {@link OperationalLogEvent}.
 * Danh sách trắng cả khóa lẫn ký tự giá trị: khóa lạ bị bỏ, giá trị ngoài {@code [A-Za-z0-9._:/{}@=*-]{1,128}} bị bỏ,
 * nên dù ai đó log nhầm thứ gì khác vào logger {@code lms.log.*} cũng không vào DB. Thuần Java, không ném lỗi.
 */
public final class LogLineParser {

    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:/{}@=*-]{1,128}");
    /** Khóa phụ được giữ trong {@code extra} (đều đã che/lọc ở IntegrationLog). */
    private static final Set<String> EXTRA_KEYS = Set.of("errorCode", "partnerCode", "retryAfter", "suppressed",
            "source", "failures", "signaturePresent", "bodyLength", "clientIp");

    private LogLineParser() {
    }

    public static Optional<OperationalLogEvent> parse(String line, String level, Instant at) {
        try {
            return doParse(line, level, at);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<OperationalLogEvent> doParse(String line, String level, Instant at) {
        if (line == null || line.isBlank() || at == null) {
            return Optional.empty();
        }
        String[] tokens = line.trim().split(" +");
        String eventToken = tokens[0];
        String channel = switch (eventToken) {
            case "integration-call" -> OperationalLogEvent.OUTBOUND;
            case "integration-receive" -> OperationalLogEvent.INBOUND;
            case "system-event" -> OperationalLogEvent.SYSTEM;
            default -> null;
        };
        if (channel == null) {
            return Optional.empty();
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (int i = 1; i < tokens.length; i++) {
            int eq = tokens[i].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String value = tokens[i].substring(eq + 1);
            if (SAFE_VALUE.matcher(value).matches() && !value.equals("-")) {
                kv.put(tokens[i].substring(0, eq), value);
            }
        }
        Map<String, String> extra = new LinkedHashMap<>();
        for (String k : EXTRA_KEYS) {
            String v = kv.get(k);
            if (v != null && !(k.equals("suppressed") && v.equals("0"))) {
                extra.put(k, v);
            }
        }
        return Optional.of(new OperationalLogEvent(UUID.randomUUID(), uuid(kv.get("tenantId")), at, channel,
                kv.get("provider"), eventToken, level == null ? "INFO" : level, kv.get("op"), kv.get("outcome"),
                integer(kv.get("httpStatus")), longValue(kv.get("durationMs")), kv.get("errorKind"),
                kv.get("errorClass"), kv.get("anomalyType"), kv.get("cid"), uuid(kv.get("intentId")), kv.get("ref"),
                extra));
    }

    private static UUID uuid(String v) {
        try {
            return v == null ? null : UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer integer(String v) {
        try {
            return v == null ? null : Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longValue(String v) {
        try {
            return v == null ? null : Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
