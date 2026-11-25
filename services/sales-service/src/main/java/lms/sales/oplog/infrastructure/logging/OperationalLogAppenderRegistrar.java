package lms.sales.oplog.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import java.util.List;
import lms.observability.log.LogChannel;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Gắn {@link OperationalLogAppender} vào ba logger kênh khi khởi động và tháo ra khi đóng context (không rò appender giữa
 * các context test). Không phải Logback thì không làm gì.
 */
public class OperationalLogAppenderRegistrar implements InitializingBean, DisposableBean {

    private static final List<String> LOGGERS = List.of(LogChannel.INTEGRATION_OUTBOUND.loggerPrefix(),
            LogChannel.INTEGRATION_INBOUND.loggerPrefix(), LogChannel.SYSTEM.loggerPrefix());

    private final OperationalLogAppender appender;

    public OperationalLogAppenderRegistrar(OperationalLogWriter writer, Level systemMinLevel) {
        this.appender = new OperationalLogAppender(writer, systemMinLevel);
    }

    @Override
    public void afterPropertiesSet() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            appender.setContext(context);
            appender.start();
            LOGGERS.forEach(name -> context.getLogger(name).addAppender(appender));
        }
    }

    @Override
    public void destroy() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            LOGGERS.forEach(name -> context.getLogger(name).detachAppender(appender));
        }
        appender.stop();
    }
}
