package lms.imports.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lms.imports.core.ChunkResult;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportHandler;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;
import lms.imports.core.ImportSessionMeta;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportSessionStore;
import lms.imports.core.ImportViews;
import lms.imports.core.IssuedCredential;
import lms.imports.core.OutcomeStatus;
import lms.imports.core.RowOutcome;
import lms.imports.core.RowStatus;
import lms.imports.core.SessionKey;
import lms.imports.core.Severity;
import lms.imports.core.SheetSpec;
import lms.imports.core.SheetStats;
import lms.imports.xlsx.CellSanitizer;
import lms.imports.xlsx.XlsxReader;
import lms.imports.xlsx.XlsxWriter;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Luồng chính (ADR-0024): upload thành kiểm thành lưu phiên; confirm chuyển trạng thái bằng CAS rồi ghi nền theo chunk.
 * Mọi thao tác dựng khóa phiên từ tenant + userId của người gọi nên người khác/tenant khác nhận NOT_FOUND. Không log dữ
 * liệu dòng: chỉ importId, type, tenant, số lượng, mã lỗi.
 */
public class ImportWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ImportWorkflow.class);
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final ImportSessionStore store;
    private final XlsxReader reader;
    private final XlsxWriter writer;
    private final UploadGate gate;
    private final ImportCommitExecutor executor;
    private final ImportLimits limits;
    private final Clock clock;
    private final Map<String, byte[]> templates = new ConcurrentHashMap<>();

    public ImportWorkflow(ImportSessionStore store, XlsxReader reader, XlsxWriter writer, UploadGate gate,
                          ImportCommitExecutor executor, ImportLimits limits, Clock clock) {
        this.store = store;
        this.reader = reader;
        this.writer = writer;
        this.gate = gate;
        this.executor = executor;
        this.limits = limits;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ template

    /** File mẫu sinh từ schema, cache trong bộ nhớ JVM (chỉ chứa dữ liệu tĩnh). */
    public byte[] template(ImportHandler handler) {
        ImportSchema s = handler.schema();
        return templates.computeIfAbsent(s.type() + "#" + s.templateVersion(), k -> writer.template(s));
    }

    // ------------------------------------------------------------------ upload

    public ImportViews.SessionView upload(ImportHandler handler, ImportActor actor, String fileName, byte[] data) {
        if (data == null || data.length == 0) throw new ImportException(ImportErrorCode.FILE_REQUIRED);
        if (data.length > limits.maxFileBytes()) throw new ImportException(ImportErrorCode.FILE_TOO_LARGE);
        ImportSchema schema = handler.schema();
        UUID importId = UUID.randomUUID();
        ImportContext ctx = actor.context(importId);

        List<ImportRow> parsed = gate.run(() -> reader.read(data, schema));
        List<ImportRow> checked = new ArrayList<>(parsed.size());
        for (ImportRow row : parsed) {
            SheetSpec spec = schema.sheet(row.sheet());
            checked.add(spec == null ? row : RowChecks.check(spec, row));
        }
        List<ImportRow> rows = handler.validate(ctx, checked);
        if (rows == null || rows.size() != checked.size()) {
            throw new IllegalStateException("ImportHandler.validate phải trả đúng số dòng đầu vào");
        }
        List<SheetStats> stats = stats(schema, rows);
        long now = clock.millis();
        ImportSessionMeta meta = ImportSessionMeta.validated(importId, schema.type(), schema.templateVersion(),
                actor.tenantId(), actor.userId(), CellSanitizer.sanitizeFileName(fileName), now,
                now + limits.sessionTtl().toMillis(), stats, 0);
        store.create(meta, rows);
        log.info("Import upload: importId={} type={} tenant={} rows={}", importId, schema.type(), actor.tenantId(), rows.size());
        return view(meta, schema, now);
    }

    private static List<SheetStats> stats(ImportSchema schema, List<ImportRow> rows) {
        List<SheetStats> out = new ArrayList<>();
        for (SheetSpec spec : schema.sheets()) {
            int total = 0;
            int valid = 0;
            int invalid = 0;
            int duplicate = 0;
            int warning = 0;
            for (ImportRow r : rows) {
                if (!r.sheet().equals(spec.name())) continue;
                total++;
                switch (r.status()) {
                    case VALID -> valid++;
                    case INVALID -> invalid++;
                    case DUPLICATE -> duplicate++;
                }
                if (r.issues().stream().anyMatch(i -> i.severity() == Severity.WARNING)) warning++;
            }
            out.add(new SheetStats(spec.name(), total, valid, invalid, duplicate, warning));
        }
        return out;
    }

    // ------------------------------------------------------------------ đọc

    public ImportViews.SessionView get(ImportHandler handler, ImportActor actor, UUID importId) {
        return view(find(handler, actor, importId), handler.schema(), clock.millis());
    }

    public ImportViews.RowsPage rows(ImportHandler handler, ImportActor actor, UUID importId, ImportViews.RowsQuery query) {
        ImportSessionMeta meta = find(handler, actor, importId);
        List<ImportRow> all = store.loadRows(meta.key());
        if (all.isEmpty()) throw new ImportException(ImportErrorCode.NOT_FOUND);
        int size = query == null || query.size() == null ? DEFAULT_PAGE_SIZE : query.size();
        size = Math.max(1, Math.min(size, limits.maxPageSize()));
        int page = query == null || query.page() == null ? 0 : Math.max(0, query.page());
        List<ImportViews.RowView> filtered = new ArrayList<>();
        for (ImportRow r : all) {
            if (query != null && query.status() != null && r.status() != query.status()) continue;
            if (query != null && query.sheet() != null && !query.sheet().isBlank() && !r.sheet().equalsIgnoreCase(query.sheet())) continue;
            filtered.add(new ImportViews.RowView(r.sheet(), r.rowNumber(), r.status(), r.values(), r.issues()));
        }
        int from = (int) Math.min((long) page * size, filtered.size());
        int to = Math.min(filtered.size(), from + size);
        int pages = (filtered.size() + size - 1) / size;
        return new ImportViews.RowsPage(List.copyOf(filtered.subList(from, to)), page, size, filtered.size(), pages);
    }

    // ------------------------------------------------------------------ confirm

    /**
     * Idempotent: bấm hai lần chỉ một lần giành được phiên (CAS), lần còn lại nhận trạng thái hiện tại của chính phiên đó.
     * Trả view; controller trả 202 (hoặc 200 khi đã COMMITTED).
     */
    public ImportViews.SessionView confirm(ImportHandler handler, ImportActor actor, UUID importId) {
        ImportSessionMeta meta = find(handler, actor, importId);
        long now = clock.millis();
        ImportSessionStatus effective = meta.effectiveStatus(now, limits.heartbeatStale().toMillis());
        if (effective != ImportSessionStatus.VALIDATED && effective != ImportSessionStatus.INTERRUPTED) {
            return view(meta, handler.schema(), now);
        }
        if (effective == ImportSessionStatus.VALIDATED && meta.toCommit() == 0) {
            throw new ImportException(ImportErrorCode.NOTHING_TO_COMMIT);
        }
        ImportContext ctx = actor.context(importId);
        List<ImportRow> rows = store.loadRows(meta.key());
        if (rows.isEmpty()) throw new ImportException(ImportErrorCode.NOT_FOUND);
        List<ImportRow> valid = rows.stream().filter(r -> r.status() == RowStatus.VALID).toList();
        List<List<ImportRow>> chunks = handler.planChunks(ctx, valid, limits.chunkSize());
        String token = UUID.randomUUID().toString();
        ImportSessionStore.BeginResult begin = store.beginCommit(meta.key(), token, chunks.size());
        if (begin.acquired()) {
            try {
                executor.submit(() -> runCommit(handler, ctx, meta.key(), token, chunks));
            } catch (ImportException busy) {
                store.releaseCommit(meta.key(), token);
                throw busy;
            }
            log.info("Import confirm: importId={} type={} tenant={} chunks={}", importId, meta.type(), actor.tenantId(), chunks.size());
        }
        return view(find(handler, actor, importId), handler.schema(), clock.millis());
    }

    /** Chạy ở luồng nền: không có SecurityContext, tenant đặt lại từ phiên trước khi handler mở transaction. */
    void runCommit(ImportHandler handler, ImportContext ctx, SessionKey key, String token, List<List<ImportRow>> chunks) {
        try {
            TenantContext.runAs(key.tenantId(), () -> doCommit(handler, ctx, key, token, chunks));
        } catch (RuntimeException e) {
            log.error("Import nền dừng bất thường: importId={} type={} lỗi={}", key.importId(), handler.type(),
                    e.getClass().getSimpleName());
        }
    }

    private void doCommit(ImportHandler handler, ImportContext ctx, SessionKey key, String token, List<List<ImportRow>> chunks) {
        ImportSessionMeta meta = store.findMeta(key).orElse(null);
        if (meta == null) return;
        int created = meta.created();
        int skipped = meta.skipped();
        int failed = meta.failed();
        int processed = meta.processed();
        boolean failedRun = false;
        for (int i = meta.nextChunk(); i < chunks.size(); i++) {
            ChunkResult result;
            try {
                result = handler.commitChunk(ctx, chunks.get(i));
            } catch (RuntimeException e) {
                log.error("Import ghi chunk lỗi: importId={} type={} chunk={} lỗi={}", key.importId(), handler.type(), i,
                        e.getClass().getSimpleName());
                failedRun = true;
                break;
            }
            created += result.count(OutcomeStatus.CREATED);
            skipped += result.count(OutcomeStatus.SKIPPED_DUPLICATE);
            failed += result.count(OutcomeStatus.FAILED);
            processed += result.outcomes().size();
            boolean owned;
            try {
                owned = store.recordProgress(key, token, new ImportSessionStore.Progress(i + 1, processed, created, skipped, failed),
                        result.outcomes(), result.credentials());
            } catch (ImportException e) {
                // Redis lỗi: không ghi tiếp chunk nào khi không lưu được tiến độ
                log.error("Import không lưu được tiến độ, dừng: importId={} type={} chunk={}", key.importId(), handler.type(), i);
                return;
            }
            if (!owned) return;
        }
        ImportSessionStatus finalStatus = failedRun ? ImportSessionStatus.COMMIT_FAILED : ImportSessionStatus.COMMITTED;
        try {
            if (!store.finish(key, token, finalStatus, failedRun ? "COMMIT_ERROR" : "")) return;
        } catch (ImportException e) {
            log.error("Import không đặt được trạng thái cuối: importId={} type={}", key.importId(), handler.type());
            return;
        }
        try {
            handler.onCompleted(ctx, new ImportResult(key.importId(), handler.type(), created, skipped, failed, !failedRun));
        } catch (RuntimeException e) {
            log.warn("Import onCompleted lỗi (bỏ qua): importId={} type={} lỗi={}", key.importId(), handler.type(),
                    e.getClass().getSimpleName());
        }
        log.info("Import xong: importId={} type={} tenant={} created={} skipped={} failed={} status={}", key.importId(),
                handler.type(), key.tenantId(), created, skipped, failed, finalStatus);
    }

    // ------------------------------------------------------------------ hủy, file lỗi, mật khẩu

    public void cancel(ImportHandler handler, ImportActor actor, UUID importId) {
        ImportSessionMeta meta = find(handler, actor, importId);
        switch (store.delete(meta.key())) {
            case DELETED -> { }
            case NOT_FOUND -> throw new ImportException(ImportErrorCode.NOT_FOUND);
            case COMMITTING -> throw new ImportException(ImportErrorCode.INVALID_STATE, "Đang ghi dữ liệu, không thể hủy");
        }
    }

    /** Dòng INVALID/DUPLICATE (preview) và dòng bị bỏ qua/lỗi khi ghi, giữ nguyên giá trị đã nhập. */
    public byte[] errorFile(ImportHandler handler, ImportActor actor, UUID importId) {
        ImportSessionMeta meta = find(handler, actor, importId);
        List<ImportRow> rows = store.loadRows(meta.key());
        if (rows.isEmpty()) throw new ImportException(ImportErrorCode.NOT_FOUND);
        Map<String, RowOutcome> outcomes = new HashMap<>();
        for (RowOutcome o : store.loadOutcomes(meta.key())) outcomes.put(o.sheet() + "#" + o.rowNumber(), o);
        List<ImportRow> errors = new ArrayList<>();
        for (ImportRow r : rows) {
            RowOutcome o = outcomes.get(r.sheet() + "#" + r.rowNumber());
            if (o != null) {
                switch (o.status()) {
                    case CREATED -> { }
                    case SKIPPED_DUPLICATE -> errors.add(new ImportRow(r.sheet(), r.rowNumber(), r.values(), RowStatus.DUPLICATE,
                            o.issues(), java.util.Map.of()));
                    case FAILED -> errors.add(new ImportRow(r.sheet(), r.rowNumber(), r.values(), RowStatus.INVALID, o.issues(),
                            java.util.Map.of()));
                }
            } else if (r.status() != RowStatus.VALID) {
                errors.add(r);
            }
        }
        return writer.errorFile(handler.schema(), errors);
    }

    /** Chỉ sau COMMITTED; lấy một lần ({@code GETDEL} tương đương). Dành cho controller của identity. */
    public byte[] credentialsFile(ImportHandler handler, ImportActor actor, UUID importId) {
        ImportSessionMeta meta = find(handler, actor, importId);
        if (meta.status() != ImportSessionStatus.COMMITTED) throw new ImportException(ImportErrorCode.INVALID_STATE);
        List<IssuedCredential> creds = store.takeCredentials(meta.key())
                .orElseThrow(() -> new ImportException(ImportErrorCode.CREDENTIALS_GONE));
        return writer.credentials(creds);
    }

    // ------------------------------------------------------------------ nội bộ

    private ImportSessionMeta find(ImportHandler handler, ImportActor actor, UUID importId) {
        SessionKey key = new SessionKey(actor.tenantId(), actor.userId(), importId);
        return store.findMeta(key).filter(m -> m.type().equals(handler.type()))
                .orElseThrow(() -> new ImportException(ImportErrorCode.NOT_FOUND));
    }

    ImportViews.SessionView view(ImportSessionMeta meta, ImportSchema schema, long now) {
        ImportSessionStatus status = meta.effectiveStatus(now, limits.heartbeatStale().toMillis());
        List<ImportViews.SheetView> sheets = new ArrayList<>();
        int total = 0;
        int valid = 0;
        int invalid = 0;
        int duplicate = 0;
        int warning = 0;
        for (SheetSpec spec : schema.sheets()) {
            SheetStats s = meta.sheets().stream().filter(x -> x.sheet().equals(spec.name())).findFirst()
                    .orElse(new SheetStats(spec.name(), 0, 0, 0, 0, 0));
            List<ImportViews.ColumnView> cols = spec.columns().stream()
                    .map(c -> new ImportViews.ColumnView(c.key(), c.label(), c.required())).toList();
            sheets.add(new ImportViews.SheetView(spec.name(), cols, s.total(), s.valid(), s.invalid(), s.duplicate(), s.warning()));
            total += s.total();
            valid += s.valid();
            invalid += s.invalid();
            duplicate += s.duplicate();
            warning += s.warning();
        }
        boolean started = meta.status() != ImportSessionStatus.VALIDATED;
        long expires = meta.expiresAt();
        if (started) {
            long last = meta.finishedAt() > 0 ? meta.finishedAt() : meta.heartbeatAt();
            expires = Math.max(expires, last + limits.sessionTtl().toMillis());
        }
        return new ImportViews.SessionView(meta.importId(), meta.type(), status, meta.fileName(),
                Instant.ofEpochMilli(meta.createdAt()), Instant.ofEpochMilli(expires), List.copyOf(sheets),
                new ImportViews.Totals(total, valid, invalid, duplicate, warning),
                started ? new ImportViews.Progress(meta.processed(), meta.toCommit()) : null,
                started ? new ImportViews.Result(meta.created(), meta.skipped(), meta.failed()) : null, meta.errorCode());
    }
}
