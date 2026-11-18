package lms.sales.stock.application.imports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lms.imports.core.ChunkResult;
import lms.imports.core.ColumnSpec;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportHandler;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;
import lms.imports.core.IssueCodes;
import lms.imports.core.RowIssue;
import lms.imports.core.RowOutcome;
import lms.imports.core.RowStatus;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.SupplierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nhập nhà cung cấp từ Excel (ADR-0024 mục 3.4.6). Khóa trùng là {@code lower(trim(name))} theo tenant (khớp chỉ mục
 * duy nhất {@code uq_suppliers_tenant_name}): trùng trong file là INVALID, đã có trong DB là DUPLICATE (bỏ qua). Nhà
 * cung cấp mới luôn active. Sau confirm xóa cache {@code sales.suppliers} (qua {@code afterImport}).
 */
public class SupplierImportHandler implements ImportHandler {

    public static final String TYPE = "suppliers";
    public static final String SHEET = "NhaCungCap";
    public static final String SUPPLIER_EXISTS = "SUPPLIER_EXISTS";

    private static final Logger log = LoggerFactory.getLogger(SupplierImportHandler.class);

    private static final ImportSchema SCHEMA = ImportSchema.single(TYPE, 1, SHEET, List.of(
            ColumnSpec.text("name", "Tên nhà cung cấp", true, 200).withExample("Nhà xuất bản Trẻ")
                    .withHint("Tối đa 200 ký tự, không phân biệt hoa thường; trùng tên nhà cung cấp đã có thì bị bỏ qua"),
            ColumnSpec.text("phone", "Điện thoại", false, 30).withExample("0281234567"),
            ColumnSpec.text("email", "Email", false, 200).withExample("lienhe@nxbtre.vn"),
            ColumnSpec.text("address", "Địa chỉ", false, 500),
            ColumnSpec.text("note", "Ghi chú", false, 1000)));

    private final SupplierRepository suppliers;
    private final SupplierImportExecutor executor;
    private final Runnable afterImport;

    /** {@code afterImport}: việc phụ sau confirm (xóa cache nhà cung cấp của tenant), chạy trong luồng đã có tenant. */
    public SupplierImportHandler(SupplierRepository suppliers, SupplierImportExecutor executor, Runnable afterImport) {
        this.suppliers = suppliers;
        this.executor = executor;
        this.afterImport = afterImport == null ? () -> { } : afterImport;
    }

    @Override
    public ImportSchema schema() {
        return SCHEMA;
    }

    @Override
    public List<ImportRow> validate(ImportContext ctx, List<ImportRow> rows) {
        int n = rows.size();
        ImportRow[] out = new ImportRow[n];
        Set<String> seen = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < n; i++) {
            ImportRow row = rows.get(i);
            out[i] = row;
            if (row.status() == RowStatus.INVALID) {
                continue;
            }
            String key = key(row);
            if (!seen.add(key)) {
                out[i] = row.invalid(RowIssue.error("name", IssueCodes.DUPLICATE_IN_FILE,
                        "Tên trùng với dòng khác trong file"));
            } else {
                keys.add(key);
            }
        }
        Set<String> existing = keys.isEmpty() ? Set.of() : suppliers.existingLowerNames(ctx.tenantId(), keys);
        for (int i = 0; i < n; i++) {
            if (out[i].status() == RowStatus.VALID && existing.contains(key(out[i]))) {
                out[i] = out[i].duplicate(RowIssue.error("name", SUPPLIER_EXISTS, "Nhà cung cấp đã tồn tại"));
            }
        }
        return new ArrayList<>(Arrays.asList(out));
    }

    static String key(ImportRow row) {
        return row.value("name").trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public ChunkResult commitChunk(ImportContext ctx, List<ImportRow> chunk) {
        try {
            return ChunkResult.of(executor.commitRows(ctx, chunk));
        } catch (RuntimeException batchFailure) {
            log.info("Import suppliers: chunk lỗi ({}), chạy lại từng dòng", batchFailure.getClass().getSimpleName());
            List<RowOutcome> outcomes = new ArrayList<>(chunk.size());
            for (ImportRow row : chunk) {
                try {
                    outcomes.addAll(executor.commitRows(ctx, List.of(row)));
                } catch (SalesException e) {
                    outcomes.add(e.code() == ErrorCode.SUPPLIER_EXISTS
                            ? RowOutcome.skipped(row, RowIssue.error("name", SUPPLIER_EXISTS, "Nhà cung cấp đã tồn tại"))
                            : RowOutcome.failed(row, RowIssue.error(null, e.code().name(), e.getMessage())));
                }
            }
            return ChunkResult.of(outcomes);
        }
    }

    @Override
    public void onCompleted(ImportContext ctx, ImportResult result) {
        try {
            executor.recordSummary(ctx, result);
        } finally {
            afterImport.run();
        }
    }
}
