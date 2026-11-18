package lms.imports.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSessionMeta;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportSessionStore;
import lms.imports.core.ImportViews;
import lms.imports.core.IssuedCredential;
import lms.imports.core.RowOutcome;
import lms.imports.core.SessionKey;
import lms.imports.core.SheetStats;
import lms.imports.engine.ImportActor;
import lms.imports.engine.ImportCommitExecutor;
import lms.imports.engine.ImportWorkflow;
import lms.imports.engine.UploadGate;
import lms.imports.testsupport.FakeHandler;
import lms.imports.testsupport.XlsxFixtures;
import lms.imports.xlsx.StreamingXlsxReader;
import lms.imports.xlsx.SxssfXlsxWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Redis thật: {@code -Dtest=RedisImportSessionStoreIT -DargLine=-Dapi.version=1.44} (cần Docker). */
class RedisImportSessionStoreIT {

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final ImportLimits limits = ImportLimits.defaults();
    private ImportSessionStore store;

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @BeforeAll
    static void start() {
        container = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
        container.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        factory.destroy();
        container.stop();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        store = new RedisImportSessionStore(factory, null, limits, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        lms.tenant.TenantContext.clear();
    }

    private ImportSessionMeta meta(String user, UUID tenant) {
        long now = System.currentTimeMillis();
        return ImportSessionMeta.validated(UUID.randomUUID(), "sample", 1, tenant, user, "f.xlsx", now,
                now + limits.sessionTtl().toMillis(), List.of(new SheetStats("DauSach", 3, 3, 0, 0, 0)), 0);
    }

    private static List<ImportRow> rows(int n) {
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(ImportRow.of("DauSach", i + 2, Map.of("name", "Sách " + i)));
        return rows;
    }

    @Test
    void createStoresRowsGzippedWithTtlAndKeyLayout() {
        ImportSessionMeta m = meta("u1", TENANT);
        store.create(m, rows(100));

        String base = "lms:import:v1:t:" + TENANT + ":u:u1:" + m.importId() + ":";
        assertThat(redis.hasKey(base + "meta")).isTrue();
        assertThat(redis.hasKey(base + "rows")).isTrue();
        assertThat(redis.opsForZSet().size("lms:import:v1:t:" + TENANT + ":u:u1:active")).isEqualTo(1);
        Long ttl = redis.getExpire(base + "meta", TimeUnit.SECONDS);
        assertThat(ttl).isBetween(1700L, 1800L);
        assertThat(redis.getExpire(base + "rows", TimeUnit.SECONDS)).isBetween(1700L, 1800L);

        ImportSessionMeta loaded = store.findMeta(m.key()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(ImportSessionStatus.VALIDATED);
        assertThat(loaded.sheets()).containsExactly(new SheetStats("DauSach", 3, 3, 0, 0, 0));
        assertThat(loaded.rowsBytes()).isPositive();
        assertThat(store.loadRows(m.key())).hasSize(100).first().satisfies(r -> assertThat(r.value("name")).isEqualTo("Sách 0"));
    }

    @Test
    void limitsThreeOpenSessionsPerUserPerTenantAndNothingLeaksAcrossUsersOrTenants() {
        ImportSessionMeta a1 = meta("u1", TENANT);
        store.create(a1, rows(1));
        store.create(meta("u1", TENANT), rows(1));
        store.create(meta("u1", TENANT), rows(1));
        assertThatThrownBy(() -> store.create(meta("u1", TENANT), rows(1))).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.SESSION_LIMIT));
        // người khác, tenant khác: có chỗ riêng
        store.create(meta("u2", TENANT), rows(1));
        UUID otherTenant = UUID.randomUUID();
        store.create(meta("u1", otherTenant), rows(1));

        // khóa dựng từ tenant+user nên người khác không đọc được cùng importId
        assertThat(store.findMeta(new SessionKey(TENANT, "u2", a1.importId()))).isEmpty();
        assertThat(store.findMeta(new SessionKey(otherTenant, "u1", a1.importId()))).isEmpty();
        assertThat(store.loadRows(new SessionKey(TENANT, "u2", a1.importId()))).isEmpty();
        // hủy một phiên thì tạo thêm được
        assertThat(store.delete(a1.key())).isEqualTo(ImportSessionStore.DeleteResult.DELETED);
        store.create(meta("u1", TENANT), rows(1));
    }

    @Test
    void rejectsRowsBlobOverTheConfiguredCeiling() {
        ImportLimits tiny = new ImportLimits(5000, 5L << 20, 50, 2000, 200, 100L << 20, 50L << 20, 0.01, 200,
                Duration.ofMinutes(30), 3, 200, 2, 4, Duration.ofMinutes(2), 200);
        ImportSessionStore small = new RedisImportSessionStore(factory, null, tiny, Clock.systemUTC());
        List<ImportRow> big = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < 200; i++) big.add(ImportRow.of("DauSach", i + 2, Map.of("name", Long.toString(rnd.nextLong()))));
        assertThatThrownBy(() -> small.create(meta("u1", TENANT), big)).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.FILE_UNSAFE));
    }

    @Test
    void casLetsExactlyOneConcurrentConfirmWin() throws Exception {
        ImportSessionMeta m = meta("u1", TENANT);
        store.create(m, rows(5));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String token = "token-" + i;
            fs.add(pool.submit(() -> {
                go.await();
                if (store.beginCommit(m.key(), token, 3).acquired()) winners.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(winners.get()).isEqualTo(1);
        ImportSessionMeta after = store.findMeta(m.key()).orElseThrow();
        assertThat(after.status()).isEqualTo(ImportSessionStatus.COMMITTING);
        assertThat(after.totalChunks()).isEqualTo(3);
        // lần sau: không giành được, nhận trạng thái hiện tại
        ImportSessionStore.BeginResult again = store.beginCommit(m.key(), "late", 3);
        assertThat(again.acquired()).isFalse();
        assertThat(again.current()).isEqualTo(ImportSessionStatus.COMMITTING);
    }

    @Test
    void progressOnlyWithMatchingTokenAndStaleSessionCanBeTakenOver() throws Exception {
        ImportLimits fast = new ImportLimits(5000, 5L << 20, 50, 2000, 200, 100L << 20, 50L << 20, 0.01, 4 << 20,
                Duration.ofMinutes(30), 3, 200, 2, 4, Duration.ofMillis(300), 200);
        ImportSessionStore s = new RedisImportSessionStore(factory, null, fast, Clock.systemUTC());
        ImportSessionMeta m = meta("u1", TENANT);
        s.create(m, rows(3));
        assertThat(s.beginCommit(m.key(), "A", 2).acquired()).isTrue();

        assertThat(s.recordProgress(m.key(), "B", new ImportSessionStore.Progress(1, 1, 1, 0, 0), List.of(), List.of())).isFalse();
        assertThat(s.recordProgress(m.key(), "A", new ImportSessionStore.Progress(1, 2, 2, 0, 0),
                List.of(RowOutcome.created(rows(1).get(0))), List.of(new IssuedCredential("an", "pw")))).isTrue();
        assertThat(s.beginCommit(m.key(), "C", 2).acquired()).isFalse(); // còn heartbeat mới

        Thread.sleep(500); // quá ngưỡng 300 ms: coi như service chết
        ImportSessionStore.BeginResult takeover = s.beginCommit(m.key(), "C", 2);
        assertThat(takeover.acquired()).isTrue();
        ImportSessionMeta resumed = s.findMeta(m.key()).orElseThrow();
        assertThat(resumed.nextChunk()).isEqualTo(1); // tiếp từ chunk chưa ghi
        assertThat(resumed.created()).isEqualTo(2);
        assertThat(s.recordProgress(m.key(), "A", new ImportSessionStore.Progress(2, 3, 3, 0, 0), List.of(), List.of())).isFalse();
        assertThat(s.finish(m.key(), "A", ImportSessionStatus.COMMITTED, "")).isFalse();
        assertThat(s.finish(m.key(), "C", ImportSessionStatus.COMMITTED, "")).isTrue();
        assertThat(s.findMeta(m.key()).orElseThrow().status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(s.loadOutcomes(m.key())).hasSize(1);
    }

    @Test
    void releaseCommitGoesBackToValidatedAndDeleteRefusesLiveCommit() {
        ImportSessionMeta m = meta("u1", TENANT);
        store.create(m, rows(2));
        assertThat(store.beginCommit(m.key(), "A", 1).acquired()).isTrue();
        assertThat(store.delete(m.key())).isEqualTo(ImportSessionStore.DeleteResult.COMMITTING);
        store.releaseCommit(m.key(), "wrong");
        assertThat(store.findMeta(m.key()).orElseThrow().status()).isEqualTo(ImportSessionStatus.COMMITTING);
        store.releaseCommit(m.key(), "A");
        assertThat(store.findMeta(m.key()).orElseThrow().status()).isEqualTo(ImportSessionStatus.VALIDATED);
        assertThat(store.delete(m.key())).isEqualTo(ImportSessionStore.DeleteResult.DELETED);
        assertThat(store.delete(m.key())).isEqualTo(ImportSessionStore.DeleteResult.NOT_FOUND);
        assertThat(store.findMeta(m.key())).isEmpty();
        assertThat(redis.keys("lms:import:v1:t:" + TENANT + ":*")).as("không còn khóa nào của phiên").isEmpty();
    }

    @Test
    void credentialsAreTakenExactlyOnce() {
        ImportSessionMeta m = meta("u1", TENANT);
        store.create(m, rows(2));
        store.beginCommit(m.key(), "A", 1);
        store.recordProgress(m.key(), "A", new ImportSessionStore.Progress(1, 2, 2, 0, 0), List.of(),
                List.of(new IssuedCredential("an", "p1"), new IssuedCredential("binh", "p-é")));

        assertThat(store.takeCredentials(m.key())).hasValueSatisfying(c -> assertThat(c)
                .containsExactly(new IssuedCredential("an", "p1"), new IssuedCredential("binh", "p-é")));
        assertThat(store.takeCredentials(m.key())).isEmpty();
    }

    @Test
    void endToEndWorkflowOnRealRedisDoubleConfirmWritesOnce() throws Exception {
        FakeHandler handler = new FakeHandler();
        handler.chunkSizeOverride = 2;
        handler.blockFirstChunk = new CountDownLatch(1);
        ImportCommitExecutor executor = new ImportCommitExecutor(4);
        ImportWorkflow workflow = new ImportWorkflow(store, new StreamingXlsxReader(limits), new SxssfXlsxWriter(limits),
                new UploadGate(2), executor, limits, Clock.systemUTC());
        ImportActor actor = new ImportActor(TENANT, "u1", Set.of(), false);
        try {
            List<Object[]> data = new ArrayList<>();
            for (String n : List.of("A", "B", "C", "D", "E")) data.add(new Object[] {null, n});
            ImportViews.SessionView v = workflow.upload(handler, actor, "ds.xlsx", XlsxFixtures.sample(data));

            ExecutorService pool = Executors.newFixedThreadPool(6);
            List<Future<ImportViews.SessionView>> fs = new ArrayList<>();
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < 6; i++) fs.add(pool.submit(() -> {
                go.await();
                return workflow.confirm(handler, actor, v.importId());
            }));
            go.countDown();
            for (Future<ImportViews.SessionView> f : fs) {
                assertThat(f.get(10, TimeUnit.SECONDS).status()).isEqualTo(ImportSessionStatus.COMMITTING);
            }
            handler.blockFirstChunk.countDown();
            pool.shutdown();

            ImportViews.SessionView done = null;
            for (int i = 0; i < 200 && (done == null || done.status() != ImportSessionStatus.COMMITTED); i++) {
                done = workflow.get(handler, actor, v.importId());
                Thread.sleep(25);
            }
            assertThat(done.status()).isEqualTo(ImportSessionStatus.COMMITTED);
            assertThat(handler.written).hasSize(5).doesNotHaveDuplicates();
            assertThat(handler.tenantsSeen).containsOnly(TENANT);
            // TTL được gia hạn khi xong
            String key = "lms:import:v1:t:" + TENANT + ":u:u1:" + v.importId() + ":meta";
            assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isGreaterThan(1700L);
        } finally {
            executor.close();
        }
    }
}
