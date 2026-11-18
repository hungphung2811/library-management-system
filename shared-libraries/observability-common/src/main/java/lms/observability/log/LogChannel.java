package lms.observability.log;

import java.util.Locale;

/**
 * Ba kênh log (ADR-0029 D1), phân biệt bằng tên logger cố định, không bằng marker hay MDC:
 * {@code lms.log.system}, {@code lms.log.outbound.<nguồn>}, {@code lms.log.inbound.<nguồn>}. Bật/tắt theo kênh bằng
 * biến môi trường chuẩn của Spring, ví dụ {@code LOGGING_LEVEL_LMS_LOG_OUTBOUND=DEBUG}.
 */
public enum LogChannel {

    SYSTEM("lms.log.system", "system-event"),
    INTEGRATION_OUTBOUND("lms.log.outbound", "integration-call"),
    INTEGRATION_INBOUND("lms.log.inbound", "integration-receive");

    private final String loggerPrefix;
    private final String eventToken;

    LogChannel(String loggerPrefix, String eventToken) {
        this.loggerPrefix = loggerPrefix;
        this.eventToken = eventToken;
    }

    /** Tiền tố logger của kênh. */
    public String loggerPrefix() {
        return loggerPrefix;
    }

    /** Token mở đầu mỗi dòng log của kênh. */
    public String eventToken() {
        return eventToken;
    }

    /** Tên logger đầy đủ; {@code source} bị lọc ký tự, viết thường; kênh SYSTEM không có hậu tố. */
    public String loggerName(String source) {
        if (this == SYSTEM || source == null || source.isBlank()) {
            return loggerPrefix;
        }
        String s = LogMask.safe(source).toLowerCase(Locale.ROOT).replace('/', '_').replace(':', '_');
        return loggerPrefix + "." + s;
    }
}
