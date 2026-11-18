package lms.identity.tenantdata.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenantdata.application.ExportPackager.PartOutput;
import lms.identity.tenantdata.application.ExportSource.SourceFailure;
import lms.identity.tenantdata.domain.ExportPart;
import lms.identity.tenantdata.domain.ExportPartRepository;
import lms.identity.tenantdata.domain.ExportStatus;
import lms.identity.tenantdata.domain.TenantDataExport;
import lms.identity.tenantdata.domain.TenantDataExportReadyEvent;
import lms.identity.tenantdata.domain.TenantDataExportRepository;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thực hiện MỘT yêu cầu xuất (chạy trên luồng nền, không chặn request). Nhận việc bằng cập nhật có điều kiện
 * PENDING sang RUNNING (an toàn khi nhiều worker), xuất lần lượt từng nguồn vào spool mã hóa (mỗi nguồn thử tối đa
 * {@code maxAttempts} lần), rồi dựng gói ZIP + manifest + chữ ký. Một nguồn lỗi hẳn thì yêu cầu FAILED nhưng vẫn thử các
 * nguồn còn lại để báo từng nguồn; lỗi chỉ lưu dạng mã chung, không lộ thông điệp nội bộ.
 */
public class RunTenantDataExportUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunTenantDataExportUseCase.class);

    private final TenantDataExportRepository exports;
    private final ExportPartRepository parts;
    private final List<ExportSource> sources;
    private final List<String> notConfigured;
    private final ExportStoragePort storage;
    private final TenantLookupPort tenants;
    private final TenantAdminsPort admins;
    private final ExportPolicy policy;
    private final ExportPackager packager;
    private final PublishPlatformEventPort events;
    private final ExportAuditor auditor;
    private final Clock clock;

    public RunTenantDataExportUseCase(TenantDataExportRepository exports, ExportPartRepository parts,
                                      List<ExportSource> sources, List<String> notConfigured,
                                      ExportStoragePort storage, TenantLookupPort tenants, TenantAdminsPort admins,
                                      ExportPolicy policy, ManifestCodec codec, ManifestSignerPort signer,
                                      PublishPlatformEventPort events, String appVersion, Clock clock) {
        this.exports = exports;
        this.parts = parts;
        this.sources = List.copyOf(sources);
        this.notConfigured = List.copyOf(notConfigured);
        this.storage = storage;
        this.tenants = tenants;
        this.admins = admins;
        this.policy = policy;
        this.packager = new ExportPackager(storage, codec, signer, appVersion);
        this.events = events;
        this.auditor = new ExportAuditor(events);
        this.clock = clock;
    }

    /** Id yêu cầu PENDING cũ nhất cho job. */
    public List<UUID> pendingIds(int limit) {
        return exports.pendingIds(limit);
    }

    /** @return true nếu yêu cầu này được nhận và chạy xong (READY hoặc FAILED); false nếu không nhận được việc. */
    public boolean run(UUID exportId) {
        if (!exports.claim(exportId, clock.instant())) {
            return false;
        }
        Optional<TenantDataExport> loaded = exports.findById(exportId);
        if (loaded.isEmpty()) {
            return false;
        }
        TenantDataExport export = loaded.get();
        try {
            execute(export);
        } catch (ExportFailure f) {
            fail(export, f.code());
        } catch (RuntimeException | IOException ex) {
            log.error("Xuất dữ liệu tenant {} thất bại ngoài dự kiến: {}", exportId, ex.toString(), ex);
            fail(export, "INTERNAL");
        } finally {
            storage.deleteByPrefix(spoolPrefix(exportId));
        }
        return true;
    }

    private void execute(TenantDataExport export) throws IOException {
        UUID id = export.id();
        UUID tenantId = export.tenantId();
        parts.deleteByExport(id);
        export.markRunning(clock.instant(), sources.size());
        exports.update(export);
        Instant now = clock.instant();
        String tenantCode = tenants.codeOf(tenantId).orElse("");
        SizeBudget budget = new SizeBudget(policy.maxBytes());
        List<PartOutput> outputs = new ArrayList<>();
        boolean anyFailed = false;
        for (ExportSource source : sources) {
            ExportPart part = ExportPart.pending(id, source.name());
            parts.save(part);
            PartOutput out = runSource(export, source, part, budget);
            if (out == null) {
                anyFailed = true;
            } else {
                outputs.add(out);
                export.partDone();
                exports.update(export);
            }
        }
        if (anyFailed) {
            throw new ExportFailure("SOURCE_FAILED");
        }
        String key = TenantDataExport.storageKeyOf(id);
        ExportPackager.Packaged packaged;
        try {
            packaged = packager.build(export, tenantCode, key, outputs, notConfigured, now);
        } catch (IOException e) {
            storage.delete(key);
            throw new ExportFailure("STORAGE_ERROR");
        }
        Instant done = clock.instant();
        export.ready(packaged.sizeBytes(), packaged.sha256(), key, done, policy.retention());
        exports.update(export);
        announceReady(export, done);
    }

    /** Xuất một nguồn với thử lại; null nếu hết lượt thử (phần đã ghi FAILED với mã chung). */
    private PartOutput runSource(TenantDataExport export, ExportSource source, ExportPart part, SizeBudget budget) {
        String lastCode = "SOURCE_ERROR";
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            part.attempt();
            parts.save(part);
            PartSpool spool = new PartSpool(storage, spoolKey(export.id(), source.name(), attempt), budget);
            try {
                source.export(export.tenantId(), export.id(), spool);
                PartSpool.Result result = spool.finish();
                part.done(result.tables(), clock.instant());
                parts.save(part);
                return new PartOutput(source.name(), result.schemaVersion(),
                        spoolKey(export.id(), source.name(), attempt), result.tables(), result.excluded());
            } catch (ExportFailure f) {
                spool.abort();
                throw f;
            } catch (SourceFailure f) {
                spool.abort();
                lastCode = f.code();
            } catch (RuntimeException ex) {
                spool.abort();
                log.warn("Nguồn {} của export {} lỗi ngoài dự kiến: {}", source.name(), export.id(), ex.toString());
                lastCode = "SOURCE_ERROR";
            }
            if (attempt < policy.maxAttempts()) {
                pause();
            }
        }
        part.failed(lastCode, clock.instant());
        parts.save(part);
        return null;
    }

    private void pause() {
        long ms = policy.retryBackoff().toMillis();
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExportFailure("INTERRUPTED");
        }
    }

    private void announceReady(TenantDataExport export, Instant now) {
        try {
            Set<UUID> recipients = new LinkedHashSet<>();
            if (!export.requestedByPlatform()) {
                recipients.add(export.requestedBy());
            }
            recipients.addAll(admins.adminsOf(export.tenantId()));
            TenantContext.runAs(export.tenantId(), () -> events.publish(
                    TenantDataExportReadyEvent.of(export, new ArrayList<>(recipients), now)));
        } catch (RuntimeException e) {
            log.warn("Không phát được TenantDataExportReady cho {}: {}", export.id(), e.toString());
        }
    }

    private void fail(TenantDataExport export, String code) {
        try {
            storage.delete(TenantDataExport.storageKeyOf(export.id()));
            if (export.status() == ExportStatus.READY) {
                return;
            }
            export.fail(code, clock.instant());
            exports.update(export);
            auditor.record(ExportAuditor.FAILED, export.requestedBy(), export.requestedByPlatform(),
                    export.tenantId(), export.id(), code);
        } catch (RuntimeException e) {
            log.error("Không ghi được trạng thái FAILED cho export {}: {}", export.id(), e.toString());
        }
    }

    static String spoolPrefix(UUID exportId) {
        return "spool-" + exportId + "-";
    }

    static String spoolKey(UUID exportId, String service, int attempt) {
        return spoolPrefix(exportId) + service + "-" + attempt + ".zip.enc";
    }
}
