package lms.imports.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportSessionStore;
import lms.imports.core.ImportViews;
import lms.imports.core.RowOutcome;
import lms.imports.core.RowStatus;
import lms.imports.core.SessionKey;
import lms.imports.testsupport.FakeHandler;
import lms.imports.testsupport.SampleSchema;
import lms.imports.testsupport.XlsxFixtures;
import lms.imports.xlsx.StreamingXlsxReader;
import lms.imports.xlsx.SxssfXlsxWriter;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImportWorkflowTest {

    static final class MutableClock extends Clock {
        volatile long millis = 1_700_000_000_000L;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private final ImportActor alice = new ImportActor(TENANT, "alice", Set.of("LENDING"), false);

    private ImportLimits limits;
    private MutableClock clock;
    private InMemoryImportSessionStore store;
    private ImportCommitExecutor executor;
    private ImportWorkflow workflow;
    private FakeHandler handler;

    @BeforeEach
    void setUp() {
        limits = ImportLimits.defaults();
        clock = new MutableClock();
        store = new InMemoryImportSessionStore(limits, clock);
        build(4);
        handler = new FakeHandler();
    }

    private void build(int queue) {
        if (executor != null) executor.close();
        executor = new ImportCommitExecutor(queue);
        workflow = new ImportWorkflow(store, new StreamingXlsxReader(limits), new SxssfXlsxWriter(limits),
                new UploadGate(2), executor, limits, clock);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        TenantContext.clear();
    }

    private static byte[] file(String... names) {
        List<Object[]> rows = new ArrayList<>();
        for (String n : names) rows.add(new Object[] {null, n});
        return XlsxFixtures.sample(rows);
    }

    private ImportViews.SessionView upload(String... names) {
        return workflow.upload(handler, alice, "..\\evil/ds sach.xlsx", file(names));
    }

    private ImportViews.SessionView await(ImportActor actor, UUID id) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            ImportViews.SessionView v = workflow.get(handler, actor, id);
            if (v.status() == ImportSessionStatus.COMMITTED || v.status() == ImportSessionStatus.COMMIT_FAILED) return v;
            Thread.sleep(25);
        }
        throw new AssertionError("Hết thời gian chờ confirm");
    }

    /**
     * Luồng nền đặt trạng thái cuối (store.finish) RỒI mới gọi handler.onCompleted: await() thấy COMMITTED/COMMIT_FAILED
     * có thể sớm hơn onCompleted một chút, nên phải chờ handler.completed thay vì assert ngay (từng gây NPE chập chờn).
     */
    private void awaitCompleted() throws InterruptedException {
        for (int i = 0; i < 200 && handler.completed == null; i++) Thread.sleep(10);
        assertThat(handler.completed).as("onCompleted chưa được gọi").isNotNull();
    }

    @Test
    void uploadBuildsPreviewWithStatusesAndNeverTouchesTheHandlerWriter() {
        ImportViews.SessionView v = upload("A", "DUP", "BAD", "WARN", "");

        assertThat(v.status()).isEqualTo(ImportSessionStatus.VALIDATED);
        assertThat(v.fileName()).isEqualTo("ds sach.xlsx");
        assertThat(v.totals()).isEqualTo(new ImportViews.Totals(4, 2, 1, 1, 1)); // dòng tên trống bị bỏ qua (không có ô nào)
        assertThat(v.sheets()).hasSize(2);
        assertThat(v.sheets().get(0).columns()).extracting(ImportViews.ColumnView::key).startsWith("isbn", "name");
        assertThat(v.progress()).isNull();
        assertThat(handler.commitCalls.get()).isZero();
        assertThat(v.expiresAt()).isAfter(v.createdAt());
    }

    @Test
    void rowsArePagedAndFilteredAndCappedAtMaxPageSize() {
        ImportViews.SessionView v = upload("A", "B", "C", "DUP", "BAD");
        UUID id = v.importId();

        ImportViews.RowsPage all = workflow.rows(handler, alice, id, new ImportViews.RowsQuery(null, null, null, 2));
        assertThat(all.items()).hasSize(2);
        assertThat(all.totalItems()).isEqualTo(5);
        assertThat(all.totalPages()).isEqualTo(3);
        assertThat(all.items().get(0).rowNumber()).isEqualTo(2);

        ImportViews.RowsPage dup = workflow.rows(handler, alice, id, new ImportViews.RowsQuery(RowStatus.DUPLICATE, "DauSach", 0, 50));
        assertThat(dup.items()).singleElement().satisfies(r -> assertThat(r.values()).containsEntry("name", "DUP"));

        assertThat(workflow.rows(handler, alice, id, new ImportViews.RowsQuery(null, null, 0, 9999)).size()).isEqualTo(200);
        assertThat(workflow.rows(handler, alice, id, new ImportViews.RowsQuery(null, "BanSao", 0, 10)).items()).isEmpty();
    }

    @Test
    void confirmWritesOnlyValidRowsInChunksUnderTheSessionTenant() throws Exception {
        handler.chunkSizeOverride = 2;
        ImportViews.SessionView v = upload("A", "B", "C", "DUP", "BAD", "D", "E");

        ImportViews.SessionView started = workflow.confirm(handler, alice, v.importId());
        assertThat(started.status()).isIn(ImportSessionStatus.COMMITTING, ImportSessionStatus.COMMITTED);

        ImportViews.SessionView done = await(alice, v.importId());
        assertThat(done.status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(done.result()).isEqualTo(new ImportViews.Result(5, 0, 0));
        assertThat(done.progress()).isEqualTo(new ImportViews.Progress(5, 5));
        assertThat(handler.written).containsExactly("DauSach#2", "DauSach#3", "DauSach#4", "DauSach#7", "DauSach#8");
        assertThat(handler.chunkSizes).containsExactly(2, 2, 1);
        assertThat(handler.tenantsSeen).containsOnly(TENANT); // luồng nền chạy dưới TenantContext của phiên
        assertThat(TenantContext.current()).isEmpty();
        awaitCompleted();
        assertThat(handler.completed.created()).isEqualTo(5);
        assertThat(handler.completed.completed()).isTrue();
    }

    @Test
    void confirmTwiceInParallelWritesEachRowOnce() throws Exception {
        handler.chunkSizeOverride = 2;
        handler.blockFirstChunk = new CountDownLatch(1);
        ImportViews.SessionView v = upload("A", "B", "C", "D", "E", "F");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<ImportViews.SessionView>> futures = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                ImportViews.SessionView r = workflow.confirm(handler, alice, v.importId());
                accepted.incrementAndGet();
                return r;
            }));
        }
        go.countDown();
        for (Future<ImportViews.SessionView> f : futures) assertThat(f.get(10, TimeUnit.SECONDS).status()).isEqualTo(ImportSessionStatus.COMMITTING);
        assertThat(handler.firstChunkEntered.await(5, TimeUnit.SECONDS)).isTrue();
        handler.blockFirstChunk.countDown();
        pool.shutdown();

        assertThat(await(alice, v.importId()).status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(handler.written).hasSize(6).doesNotHaveDuplicates();
        assertThat(handler.commitCalls.get()).isEqualTo(3);

        // bấm lại sau khi xong: không ghi lại, trả trạng thái hiện tại
        assertThat(workflow.confirm(handler, alice, v.importId()).status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(handler.written).hasSize(6);
    }

    @Test
    void otherUserOrTenantSeesNotFoundForEverything() {
        UUID id = upload("A").importId();
        ImportActor bob = new ImportActor(TENANT, "bob", Set.of(), false);
        ImportActor aliceElsewhere = new ImportActor(OTHER_TENANT, "alice", Set.of(), false);

        for (ImportActor stranger : List.of(bob, aliceElsewhere)) {
            assertNotFound(() -> workflow.get(handler, stranger, id));
            assertNotFound(() -> workflow.rows(handler, stranger, id, null));
            assertNotFound(() -> workflow.confirm(handler, stranger, id));
            assertNotFound(() -> workflow.cancel(handler, stranger, id));
            assertNotFound(() -> workflow.errorFile(handler, stranger, id));
            assertNotFound(() -> workflow.credentialsFile(handler, stranger, id));
        }
        assertNotFound(() -> workflow.get(handler, alice, UUID.randomUUID()));
        assertThat(workflow.get(handler, alice, id).importId()).isEqualTo(id);
    }

    private static void assertNotFound(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.NOT_FOUND));
    }

    @Test
    void confirmWithoutValidRowsIsNothingToCommit() {
        UUID id = upload("BAD", "DUP").importId();
        assertThatThrownBy(() -> workflow.confirm(handler, alice, id)).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.NOTHING_TO_COMMIT));
    }

    @Test
    void atMostThreeOpenSessionsPerUser() {
        UUID first = upload("A").importId();
        upload("B");
        upload("C");
        assertThatThrownBy(() -> upload("D")).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.SESSION_LIMIT));
        // người khác không bị ảnh hưởng
        workflow.upload(handler, new ImportActor(TENANT, "bob", Set.of(), false), "x.xlsx", file("A"));
        workflow.cancel(handler, alice, first);
        upload("E");
    }

    @Test
    void cancelWhileCommittingIsInvalidStateAndAfterwardsSessionIsGone() throws Exception {
        handler.blockFirstChunk = new CountDownLatch(1);
        UUID id = upload("A", "B").importId();
        workflow.confirm(handler, alice, id);
        assertThat(handler.firstChunkEntered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> workflow.cancel(handler, alice, id)).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.INVALID_STATE));
        handler.blockFirstChunk.countDown();
        await(alice, id);

        workflow.cancel(handler, alice, id);
        assertNotFound(() -> workflow.get(handler, alice, id));
    }

    @Test
    void unexpectedHandlerFailureMarksCommitFailedKeepingEarlierChunks() throws Exception {
        handler.chunkSizeOverride = 2;
        handler.failOnChunkCall = 2;
        UUID id = upload("A", "B", "C", "D").importId();
        workflow.confirm(handler, alice, id);

        ImportViews.SessionView v = await(alice, id);
        assertThat(v.status()).isEqualTo(ImportSessionStatus.COMMIT_FAILED);
        assertThat(v.errorCode()).isEqualTo("COMMIT_ERROR");
        assertThat(v.result().created()).isEqualTo(2);
        awaitCompleted();
        assertThat(handler.completed.completed()).isFalse();
    }

    @Test
    void interruptedSessionResumesFromNextChunk() throws Exception {
        handler.chunkSizeOverride = 2;
        UUID id = upload("A", "B", "C", "D", "E").importId();
        SessionKey key = new SessionKey(TENANT, "alice", id);
        // giả lập service chết sau chunk 0
        store.beginCommit(key, "old-token", 3);
        store.recordProgress(key, "old-token", new ImportSessionStore.Progress(1, 2, 2, 0, 0),
                List.of(RowOutcome.created(store.loadRows(key).get(0)), RowOutcome.created(store.loadRows(key).get(1))), List.of());
        assertThat(workflow.get(handler, alice, id).status()).isEqualTo(ImportSessionStatus.COMMITTING);

        clock.millis += 3 * 60_000; // quá 2 phút không heartbeat
        assertThat(workflow.get(handler, alice, id).status()).isEqualTo(ImportSessionStatus.INTERRUPTED);

        workflow.confirm(handler, alice, id);
        ImportViews.SessionView v = await(alice, id);

        assertThat(v.status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(handler.chunkSizes).containsExactly(2, 1); // chunk 0 không ghi lại
        assertThat(v.result().created()).isEqualTo(5);
        // luồng cũ "sống lại" với token cũ không ghi đè được
        assertThat(store.recordProgress(key, "old-token", new ImportSessionStore.Progress(0, 0, 0, 0, 0), List.of(), List.of())).isFalse();
        assertThat(store.finish(key, "old-token", ImportSessionStatus.COMMIT_FAILED, "X")).isFalse();
    }

    @Test
    void queueFullGivesBusyAndLeavesSessionValidated() throws Exception {
        build(1);
        handler.blockFirstChunk = new CountDownLatch(1);
        UUID first = upload("A").importId();
        UUID second = upload("B").importId();
        UUID third = upload("C").importId();

        workflow.confirm(handler, alice, first);
        assertThat(handler.firstChunkEntered.await(5, TimeUnit.SECONDS)).isTrue(); // luồng duy nhất bận
        workflow.confirm(handler, alice, second); // vào hàng đợi (dung lượng 1)
        assertThatThrownBy(() -> workflow.confirm(handler, alice, third)).isInstanceOfSatisfying(ImportException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ImportErrorCode.BUSY);
            assertThat(e.details()).anyMatch(d -> d.startsWith("retryAfterSeconds="));
        });
        assertThat(workflow.get(handler, alice, third).status()).isEqualTo(ImportSessionStatus.VALIDATED);

        handler.blockFirstChunk.countDown();
        await(alice, first);
        await(alice, second);
        workflow.confirm(handler, alice, third); // giờ thì được
        assertThat(await(alice, third).status()).isEqualTo(ImportSessionStatus.COMMITTED);
    }

    @Test
    void errorFileCombinesPreviewErrorsAndCommitOutcomesWithoutValidCreatedRows() throws Exception {
        UUID id = upload("A", "DUP", "BAD").importId();
        workflow.confirm(handler, alice, id);
        await(alice, id);

        byte[] error = workflow.errorFile(handler, alice, id);

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(error))) {
            org.apache.poi.xssf.usermodel.XSSFSheet s = wb.getSheet("DauSach");
            assertThat(s.getLastRowNum()).isEqualTo(2); // DUP và BAD; "A" đã tạo nên không có
            assertThat(s.getRow(1).getCell(1).getStringCellValue()).isEqualTo("DUP");
            assertThat(s.getRow(2).getCell(1).getStringCellValue()).isEqualTo("BAD");
        }
    }

    @Test
    void credentialsAreDownloadableOnceAfterCommit() throws Exception {
        handler.withCredentials = true;
        UUID id = upload("A", "B").importId();
        assertThatThrownBy(() -> workflow.credentialsFile(handler, alice, id)).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.INVALID_STATE));
        workflow.confirm(handler, alice, id);
        await(alice, id);

        byte[] first = workflow.credentialsFile(handler, alice, id);
        assertThat(first).isNotEmpty();
        assertThatThrownBy(() -> workflow.credentialsFile(handler, alice, id)).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.CREDENTIALS_GONE));
    }

    @Test
    void rejectsEmptyAndOversizeUploadsBeforeParsing() {
        assertThatThrownBy(() -> workflow.upload(handler, alice, "a.xlsx", new byte[0])).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.FILE_REQUIRED));
        assertThatThrownBy(() -> workflow.upload(handler, alice, "a.xlsx", new byte[(int) limits.maxFileBytes() + 1]))
                .isInstanceOfSatisfying(ImportException.class, e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.FILE_TOO_LARGE));
    }

    @Test
    void uploadGateReturnsBusyWhenAllPermitsAreTaken() throws Exception {
        UploadGate gate = new UploadGate(1);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread t = new Thread(() -> gate.run(() -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        t.start();
        inside.await();
        assertThatThrownBy(() -> gate.run(() -> null)).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.BUSY));
        release.countDown();
        t.join();
        assertThat(gate.run(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void templateIsGeneratedOnceAndCached() {
        assertThat(workflow.template(handler)).isSameAs(workflow.template(handler));
        assertThat(SampleSchema.schema().type()).isEqualTo(handler.type());
    }
}
