package lms.sales.oplog.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import lms.sales.oplog.domain.OperationalLogEvent;

/**
 * Appender Logback gắn vào {@code lms.log.outbound}, {@code lms.log.inbound}, {@code lms.log.system}: phân tích dòng
 * {@code key=value} rồi đẩy vào {@link OperationalLogWriter}. Chạy trên luồng nghiệp vụ nên chỉ parse + offer (không
 * chặn) và nuốt mọi lỗi. Kênh SYSTEM chọn lọc: chỉ lưu khi mức log từ {@code systemMinLevel} trở lên hoặc có bất thường.
 */
public class OperationalLogAppender extends AppenderBase<ILoggingEvent> {

    private final OperationalLogWriter writer;
    private final Level systemMinLevel;

    public OperationalLogAppender(OperationalLogWriter writer, Level systemMinLevel) {
        this.writer = writer;
        this.systemMinLevel = systemMinLevel == null ? Level.WARN : systemMinLevel;
        setName("oplog-appender");
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            if (event == null || event.getLevel() == null) {
                return;
            }
            LogLineParser.parse(event.getFormattedMessage(), event.getLevel().toString(),
                            Instant.ofEpochMilli(event.getTimeStamp()))
                    .filter(e -> !OperationalLogEvent.SYSTEM.equals(e.channel()) || e.anomalyType() != null
                            || event.getLevel().isGreaterOrEqual(systemMinLevel))
                    .ifPresent(writer::offer);
        } catch (RuntimeException e) {
            // không bao giờ ném lỗi ra luồng nghiệp vụ
        }
    }
}
