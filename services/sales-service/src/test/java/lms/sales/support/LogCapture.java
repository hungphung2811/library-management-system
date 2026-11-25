package lms.sales.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Bắt mọi dòng log (appender gắn vào root, mức DEBUG trong lúc bắt) để test rò rỉ kiểm TOÀN BỘ dòng log, không chỉ
 * kênh tích hợp. Dòng gồm thông điệp đã định dạng và, nếu có, tên lớp + thông điệp + stack của throwable.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final Logger lmsLog = (Logger) LoggerFactory.getLogger("lms.log");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level rootBefore = root.getLevel();
    private final Level lmsBefore = lmsLog.getLevel();

    private LogCapture(Level lmsLogLevel) {
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
        lmsLog.setLevel(lmsLogLevel);
    }

    /** Bắt với mức DEBUG cho kênh {@code lms.log.*}. */
    public static LogCapture debug() {
        return new LogCapture(Level.DEBUG);
    }

    /** Bắt với mức INFO cho kênh {@code lms.log.*} (như cấu hình mặc định). */
    public static LogCapture info() {
        return new LogCapture(Level.INFO);
    }

    /** Mọi sự kiện, trừ log của chính máy chủ HTTP giả của test (nó in dòng yêu cầu thô, không phải mã của dự án). */
    public List<ILoggingEvent> events() {
        return appender.list.stream().filter(e -> !e.getLoggerName().startsWith("com.sun.net.httpserver")
                && !e.getLoggerName().startsWith("sun.net")).toList();
    }

    /** Mọi dòng, kèm throwable (lớp, thông điệp, stack) nếu có: dùng cho khẳng định "không chứa chuỗi nhạy cảm". */
    public List<String> allLines() {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : events()) {
            StringBuilder sb = new StringBuilder(e.getFormattedMessage());
            var t = e.getThrowableProxy();
            while (t != null) {
                sb.append(' ').append(t.getClassName()).append(' ').append(t.getMessage());
                for (var el : t.getStackTraceElementProxyArray()) {
                    sb.append(' ').append(el.getSTEAsString());
                }
                t = t.getCause();
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** Sự kiện của kênh tích hợp ({@code lms.log.*}). */
    public List<ILoggingEvent> channelEvents() {
        return appender.list.stream().filter(e -> e.getLoggerName().startsWith("lms.log.")).toList();
    }

    /** Thông điệp của kênh tích hợp chứa đoạn {@code part}. */
    public List<ILoggingEvent> channelEvents(String part) {
        return channelEvents().stream().filter(e -> e.getFormattedMessage().contains(part)).toList();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(rootBefore);
        lmsLog.setLevel(lmsBefore);
    }
}
