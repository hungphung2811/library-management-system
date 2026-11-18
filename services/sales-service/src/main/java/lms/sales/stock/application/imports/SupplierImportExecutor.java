package lms.sales.stock.application.imports;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.RowIssue;
import lms.imports.core.RowOutcome;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.SupplierRepository;
import lms.sales.stock.domain.Supplier;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi một chunk nhà cung cấp trong MỘT transaction: kiểm lại tên đã có bằng MỘT truy vấn, chèn một lần (JDBC batch).
 * Không audit từng dòng (chỉ một bản ghi tóm tắt mỗi lần confirm, {@link #recordSummary}).
 */
public class SupplierImportExecutor {

    private final SupplierRepository suppliers;
    private final SalesAuditPort audit;
    private final Clock clock;

    public SupplierImportExecutor(SupplierRepository suppliers, SalesAuditPort audit, Clock clock) {
        this.suppliers = suppliers;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public List<RowOutcome> commitRows(ImportContext ctx, List<ImportRow> rows) {
        UUID tenant = ctx.tenantId();
        Set<String> keys = new HashSet<>();
        for (ImportRow r : rows) {
            keys.add(SupplierImportHandler.key(r));
        }
        Set<String> existing = suppliers.existingLowerNames(tenant, keys);
        Set<String> seen = new HashSet<>();
        List<Supplier> toInsert = new ArrayList<>();
        List<RowOutcome> outcomes = new ArrayList<>(rows.size());
        for (ImportRow r : rows) {
            String key = SupplierImportHandler.key(r);
            if (existing.contains(key) || !seen.add(key)) {
                outcomes.add(RowOutcome.skipped(r, RowIssue.error("name", SupplierImportHandler.SUPPLIER_EXISTS,
                        "Nhà cung cấp đã tồn tại")));
                continue;
            }
            try {
                toInsert.add(Supplier.create(tenant, r.value("name"), r.value("phone"), r.value("email"),
                        r.value("address"), r.value("note")));
                outcomes.add(RowOutcome.created(r));
            } catch (SalesException e) {
                outcomes.add(RowOutcome.failed(r, RowIssue.error("name", e.code().name(), e.getMessage())));
            }
        }
        suppliers.insertAll(toInsert);
        return outcomes;
    }

    @Transactional
    public void recordSummary(ImportContext ctx, ImportResult result) {
        audit.record(new SalesAuditPort.AuditEntry(ctx.tenantId(), SalesAuditPort.Actions.SUPPLIERS_IMPORTED,
                "SupplierImport", ctx.importId(), StockItemImportExecutor.parseUuid(ctx.userId()), clock.instant(),
                Map.of("importId", String.valueOf(result.importId()), "created", result.created(),
                        "skippedDuplicate", result.skippedDuplicate(), "failed", result.failed(),
                        "completed", result.completed())));
    }
}
