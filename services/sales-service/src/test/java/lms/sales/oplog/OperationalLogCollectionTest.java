package lms.sales.oplog;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lms.observability.log.IntegrationLog;
import lms.observability.log.LogChannel;
import lms.sales.oplog.application.OperationalLogStore;
import lms.sales.oplog.domain.OperationalLogEvent;
import lms.sales.oplog.infrastructure.logging.LogLineParser;
import lms.sales.oplog.infrastructure.logging.OperationalLogAppenderRegistrar;
import lms.sales.oplog.infrastructure.logging.OperationalLogWriter;
import lms.sales.shared.application.PageResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Parser dòng log, hàng đợi ghi không chặn, và đường đi thật IntegrationLog -> Logback appender -> writer. */
class OperationalLogCollectionTest {

    private static final Instant AT = Instant.parse("2026-11-19T01:02:03Z");
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID INTENT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    /** Store giả: gom lô, tùy chọn chặn để mô phỏng DB chậm. */
    static class FakeStore implements OperationalLogStore {
        final List<OperationalLogEvent> saved = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch gate;
        volatile CountDownLatch saw = new CountDownLatch(1);

        FakeStore(CountDownLatch gate) {
            this.gate = gate;
        }

        @Override
        public void saveBatch(List<OperationalLogEvent> events) {
            saw.countDown();
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            saved.addAll(events);
        }

        @Override
        public PageResult<OperationalLogEvent> search(Criteria criteria, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int purgeOlderThan(Instant cutoff, int limit) {
            return 0;
        }
    }

    @Test
    void parserDocDayDuTruongLoiVaBoQuaKhoaLa() {
        String line = "integration-call channel=INTEGRATION_OUTBOUND provider=momo op=create cid=abc-1 tenantId=" + TENANT
                + " intentId=" + INTENT + " ref=ORD*****1234 outcome=FAILURE httpStatus=502 durationMs=830 errorCode=-"
                + " errorKind=SERVER_ERROR errorClass=SocketTimeoutException partnerCode=99 retryAfter=- suppressed=0"
                + " anomaly=true anomalyType=PAYMENT_MISMATCH secret=hunter2 body={card:4111} phone=0912345678";
        OperationalLogEvent e = LogLineParser.parse(line, "WARN", AT).orElseThrow();
        assertThat(e.channel()).isEqualTo(OperationalLogEvent.OUTBOUND);
        assertThat(e.event()).isEqualTo("integration-call");
        assertThat(e.provider()).isEqualTo("momo");
        assertThat(e.tenantId()).isEqualTo(TENANT);
        assertThat(e.intentId()).isEqualTo(INTENT);
        assertThat(e.httpStatus()).isEqualTo(502);
        assertThat(e.durationMs()).isEqualTo(830L);
        assertThat(e.anomalyType()).isEqualTo("PAYMENT_MISMATCH");
        assertThat(e.correlationId()).isEqualTo("abc-1");
        assertThat(e.reference()).isEqualTo("ORD*****1234");
        assertThat(e.level()).isEqualTo("WARN");
        assertThat(e.extra()).containsEntry("partnerCode", "99").doesNotContainKeys("retryAfter", "suppressed");
        // khóa lạ (secret, body, phone) không vào đâu cả
        assertThat(e.toString()).doesNotContain("hunter2").doesNotContain("4111").doesNotContain("0912345678");
        assertThat(e.extra().keySet()).doesNotContain("secret", "body", "phone");
    }

    @Test
    void parserTuChoiDongKhongPhaiLogTichHopVaGiaTriLa() {
        assertThat(LogLineParser.parse("hello world", "INFO", AT)).isEmpty();
        assertThat(LogLineParser.parse(null, "INFO", AT)).isEmpty();
        assertThat(LogLineParser.parse("", "INFO", AT)).isEmpty();
        // tenantId không phải UUID và giá trị chứa ký tự ngoài danh sách trắng đều bị bỏ, không làm hỏng cả dòng
        OperationalLogEvent e = LogLineParser.parse(
                "integration-receive channel=INTEGRATION_INBOUND provider=sepay tenantId=not-a-uuid op=<script>x httpStatus=abc",
                "INFO", AT).orElseThrow();
        assertThat(e.channel()).isEqualTo(OperationalLogEvent.INBOUND);
        assertThat(e.tenantId()).isNull();
        assertThat(e.op()).isNull();
        assertThat(e.httpStatus()).isNull();
    }

    @Test
    void hangDoiDayThiBoVaDemKhongChan() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        FakeStore store = new FakeStore(gate);
        OperationalLogWriter writer = new OperationalLogWriter(store, 3, 1);
        writer.start();
        try {
            OperationalLogEvent e = LogLineParser.parse("system-event channel=SYSTEM op=x", "WARN", AT).orElseThrow();
            assertThat(writer.offer(e)).isTrue();
            // luồng ghi nhận dòng đầu rồi kẹt ở store; hàng đợi (3) còn trống 3 chỗ
            assertThat(store.saw.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            int accepted = 0;
            for (int i = 0; i < 10; i++) {
                if (writer.offer(e)) {
                    accepted++;
                }
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(accepted).isEqualTo(3);
            assertThat(writer.dropped()).isEqualTo(7);
            assertThat(ms).as("offer không được chặn khi đầy").isLessThan(500);
        } finally {
            gate.countDown();
            writer.stop();
        }
        assertThat(store.saved).hasSize(4);
    }

    @Test
    void loiGhiDbKhongLamChetLuongGhi() throws Exception {
        List<Integer> calls = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch twice = new CountDownLatch(2);
        OperationalLogStore flaky = new FakeStore(null) {
            @Override
            public void saveBatch(List<OperationalLogEvent> events) {
                calls.add(events.size());
                twice.countDown();
                if (calls.size() == 1) {
                    throw new IllegalStateException("db down");
                }
            }
        };
        OperationalLogWriter writer = new OperationalLogWriter(flaky, 10, 1);
        writer.start();
        try {
            OperationalLogEvent e = LogLineParser.parse("system-event channel=SYSTEM op=x", "WARN", AT).orElseThrow();
            writer.offer(e);
            writer.offer(e);
            assertThat(twice.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            writer.stop();
        }
        assertThat(writer.failed()).isEqualTo(1);
        assertThat(writer.written()).isEqualTo(1);
    }

    @Test
    void duongDiThatIntegrationLogQuaLogbackVaoWriterVaChonLocKenhSystem() throws Exception {
        FakeStore store = new FakeStore(null);
        OperationalLogWriter writer = new OperationalLogWriter(store, 100, 50);
        OperationalLogAppenderRegistrar registrar = new OperationalLogAppenderRegistrar(writer, Level.WARN);
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger out = ctx.getLogger("lms.log.outbound.testprov");
        Level before = out.getLevel();
        out.setLevel(Level.INFO);
        ch.qos.logback.classic.Logger system = ctx.getLogger("lms.log.system");
        Level systemBefore = system.getLevel();
        system.setLevel(Level.INFO);
        registrar.afterPropertiesSet();
        writer.start();
        try {
            IntegrationLog.of(LogChannel.INTEGRATION_OUTBOUND, "testprov").entry().provider("testprov").op("create")
                    .tenantId(TENANT).intentId(INTENT).reference("ORD-20261119-0001").outcome("SUCCESS").httpStatus(200)
                    .durationMs(12).info();
            IntegrationLog.of(LogChannel.SYSTEM, null).entry().op("boot").outcome("OK").info();
            IntegrationLog.of(LogChannel.SYSTEM, null).entry().op("sweep").outcome("FAILED").warn();
            IntegrationLog.of(LogChannel.SYSTEM, null).entry().op("mismatch").anomaly("X_TYPE").info();
            // dòng log thường của logger khác không vào
            LoggerFactory.getLogger("lms.sales.other").info("integration-call channel=INTEGRATION_OUTBOUND provider=evil");
        } finally {
            registrar.destroy();
            writer.stop();
            out.setLevel(before);
            system.setLevel(systemBefore);
        }
        assertThat(store.saved).extracting(OperationalLogEvent::op).containsExactlyInAnyOrder("create", "sweep", "mismatch");
        OperationalLogEvent created = store.saved.stream().filter(e -> "create".equals(e.op())).findFirst().orElseThrow();
        assertThat(created.tenantId()).isEqualTo(TENANT);
        assertThat(created.reference()).doesNotContain("20261119").startsWith("ORD");
        assertThat(created.httpStatus()).isEqualTo(200);
        // sau destroy appender đã được gỡ: log tiếp không vào writer
        int queuedBefore = writer.queued();
        IntegrationLog.of(LogChannel.SYSTEM, null).entry().op("late").outcome("X").warn();
        assertThat(writer.queued()).isEqualTo(queuedBefore);
    }
}
