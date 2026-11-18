package lms.observability.log;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class IntegrationLogTest {

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("lms.log.outbound.sepay");
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(null);
        MDC.clear();
    }

    @Test
    void tenLoggerTheoKenhVaNguon() {
        assertThat(LogChannel.INTEGRATION_OUTBOUND.loggerName("SEPAY")).isEqualTo("lms.log.outbound.sepay");
        assertThat(LogChannel.INTEGRATION_INBOUND.loggerName("webhook")).isEqualTo("lms.log.inbound.webhook");
        assertThat(LogChannel.SYSTEM.loggerName("x")).isEqualTo("lms.log.system");
        assertThat(LogChannel.INTEGRATION_OUTBOUND.eventToken()).isEqualTo("integration-call");
        assertThat(LogChannel.INTEGRATION_INBOUND.eventToken()).isEqualTo("integration-receive");
        assertThat(LogChannel.SYSTEM.eventToken()).isEqualTo("system-event");
    }

    @Test
    void thuTuTruongCoDinhVaThieuThi() {
        MDC.put("correlationId", "cid-1");
        UUID tenant = UUID.randomUUID();
        UUID intent = UUID.randomUUID();
        IntegrationLog.of(LogChannel.INTEGRATION_OUTBOUND, "sepay").entry().provider("SEPAY").op("queryPayment")
                .tenantId(tenant).intentId(intent).reference("LMSABCDEFGHJK").outcome("FAIL").durationMs(10003)
                .errorCode("PAYMENT_GATEWAY_ERROR").errorKind("TIMEOUT")
                .errorClass(new RuntimeException("x", new java.net.http.HttpTimeoutException("secret url?a=b")))
                .warn();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo(
                "integration-call channel=INTEGRATION_OUTBOUND provider=SEPAY op=queryPayment cid=cid-1 tenantId="
                        + tenant + " intentId=" + intent + " ref=LMS******GHJK outcome=FAIL httpStatus=- "
                        + "durationMs=10003 errorCode=PAYMENT_GATEWAY_ERROR errorKind=TIMEOUT "
                        + "errorClass=HttpTimeoutException partnerCode=- retryAfter=- suppressed=0 anomaly=false "
                        + "anomalyType=-");
    }

    @Test
    void errorClassKhongBaoGioChuaThongDiepHayStackTrace() {
        IntegrationLog.of(LogChannel.INTEGRATION_OUTBOUND, "sepay").entry().provider("SEPAY").op("x")
                .errorClass(new IllegalStateException("Bearer tok_SECRET_should_not_leak https://h/?q=1")).warn();
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(line).contains("errorClass=IllegalStateException").doesNotContain("tok_SECRET")
                .doesNotContain("Bearer").doesNotContain("?");
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void danhDauBatThuongVaTruongPhu() {
        IntegrationLog.of(LogChannel.INTEGRATION_OUTBOUND, "sepay").entry().provider("SEPAY").op("queryPayment")
                .anomaly("GATEWAY_RATE_LIMITED").retryAfter("30").suppressed(4).source("POLL").failures(6)
                .clientIp("203.0.113.9").signaturePresent(false).bodyLength(12).partnerCode("42").error();
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(line).contains("retryAfter=30", "suppressed=4", "anomaly=true", "anomalyType=GATEWAY_RATE_LIMITED",
                "partnerCode=42", "source=POLL", "failures=6", "signaturePresent=false", "bodyLength=12",
                "clientIp=203.0.113.x");
    }

    @Test
    void giaTriLaBiLocKhongChenDuocDongLog() {
        IntegrationLog.of(LogChannel.INTEGRATION_OUTBOUND, "sepay").entry().provider("SEPAY\nFAKE line=1")
                .partnerCode("bad code\r\n").retryAfter("abc").info();
        assertThat(appender.list).hasSize(1);
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(line).doesNotContain("\n").doesNotContain("\r").contains("partnerCode=-", "retryAfter=-");
    }

    @Test
    void khongDungChuoiKhiMucLogTat() {
        logger.setLevel(Level.WARN);
        IntegrationLog log = IntegrationLog.of(LogChannel.INTEGRATION_OUTBOUND, "sepay");
        assertThat(log.isDebugEnabled()).isFalse();
        assertThat(log.isInfoEnabled()).isFalse();
        log.entry().provider("SEPAY").outcome("NO_MATCH").debug();
        log.entry().provider("SEPAY").outcome("OK").info();
        assertThat(appender.list).isEmpty();
        log.entry().provider("SEPAY").outcome("FAIL").warn();
        assertThat(appender.list).hasSize(1);
    }
}
