package lms.imports.testsupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import lms.imports.core.ChunkResult;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportHandler;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;
import lms.imports.core.IssuedCredential;
import lms.imports.core.RowIssue;
import lms.imports.core.RowOutcome;
import lms.imports.core.RowStatus;
import lms.tenant.TenantContext;

/** Handler giả: tên "DUP" là trùng DB, "BAD" lỗi nghiệp vụ; ghi lại mọi dòng đã "ghi". */
public class FakeHandler implements ImportHandler {

    public final List<String> written = Collections.synchronizedList(new ArrayList<>());
    public final List<Integer> chunkSizes = Collections.synchronizedList(new ArrayList<>());
    public final List<java.util.UUID> tenantsSeen = Collections.synchronizedList(new ArrayList<>());
    public final AtomicInteger commitCalls = new AtomicInteger();
    public volatile CountDownLatch blockFirstChunk;
    public volatile CountDownLatch firstChunkEntered = new CountDownLatch(1);
    public volatile int failOnChunkCall = -1;
    public volatile ImportResult completed;
    public volatile boolean withCredentials;
    public volatile int chunkSizeOverride = 0;

    @Override
    public ImportSchema schema() {
        return SampleSchema.schema();
    }

    @Override
    public List<ImportRow> validate(ImportContext ctx, List<ImportRow> rows) {
        List<ImportRow> out = new ArrayList<>();
        for (ImportRow r : rows) {
            if (r.status() == RowStatus.INVALID) {
                out.add(r);
            } else if ("DUP".equals(r.value("name"))) {
                out.add(r.duplicate(RowIssue.error("name", "ALREADY_EXISTS", "Đã tồn tại")));
            } else if ("BAD".equals(r.value("name"))) {
                out.add(r.invalid(RowIssue.error("name", "BAD", "Sai")));
            } else if ("WARN".equals(r.value("name"))) {
                out.add(r.withIssue(RowIssue.warning("isbn", "NO_DEDUPE_KEY", "Không có khóa chống trùng")));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public ChunkResult commitChunk(ImportContext ctx, List<ImportRow> chunk) {
        int call = commitCalls.incrementAndGet();
        tenantsSeen.add(TenantContext.current().orElse(null));
        chunkSizes.add(chunk.size());
        if (call == 1) {
            firstChunkEntered.countDown();
            CountDownLatch latch = blockFirstChunk;
            if (latch != null) {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (failOnChunkCall == call) throw new IllegalStateException("lỗi giả lập");
        List<RowOutcome> outcomes = new ArrayList<>();
        List<IssuedCredential> creds = new ArrayList<>();
        for (ImportRow r : chunk) {
            written.add(r.sheet() + "#" + r.rowNumber());
            outcomes.add(RowOutcome.created(r));
            if (withCredentials) creds.add(new IssuedCredential("user" + r.rowNumber(), "pw-" + r.rowNumber()));
        }
        return new ChunkResult(outcomes, creds);
    }

    @Override
    public List<List<ImportRow>> planChunks(ImportContext ctx, List<ImportRow> validRows, int chunkSize) {
        return ImportHandler.super.planChunks(ctx, validRows, chunkSizeOverride > 0 ? chunkSizeOverride : chunkSize);
    }

    @Override
    public void onCompleted(ImportContext ctx, ImportResult result) {
        this.completed = result;
    }
}
