package lms.sales.stock.application.imports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.imports.core.ChunkResult;
import lms.imports.core.ColumnSpec;
import lms.imports.core.ColumnType;
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
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.domain.StockItemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nhập mặt hàng tồn kho từ Excel (ADR-0024 mục 3.4.5, Q4, 5.7). Validate (luồng request, có token người dùng): tra
 * ISBN -> titleId bằng MỘT lời gọi catalog theo lô, kiểm trùng (tenant, title) và SKU bằng MỘT truy vấn mỗi loại; titleId
 * và giá bìa lưu vào {@code ImportRow.extra} vì luồng confirm nền KHÔNG có token (không gọi catalog nữa). Commit: ghi theo
 * chunk (xem {@link StockItemImportExecutor}). Lỗi catalog không khả dụng ném SalesException(CATALOG_UNAVAILABLE, 503).
 * Trùng (tenant, title) hoặc SKU đã có trong DB là DUPLICATE (bỏ qua); trùng trong file là INVALID.
 */
public class StockItemImportHandler implements ImportHandler {

    public static final String TYPE = "stock-items";
    public static final String SHEET = "MatHang";
    static final String EXTRA_TITLE_ID = "titleId";
    static final String EXTRA_COVER_PRICE = "coverPrice";

    public static final String TITLE_NOT_FOUND = "TITLE_NOT_FOUND";
    public static final String STOCK_ITEM_EXISTS = "STOCK_ITEM_EXISTS";
    public static final String SKU_EXISTS = "SKU_EXISTS";

    private static final Logger log = LoggerFactory.getLogger(StockItemImportHandler.class);

    private static final ImportSchema SCHEMA = ImportSchema.single(TYPE, 1, SHEET, List.of(
            ColumnSpec.text("isbn", "ISBN đầu sách", true, 20).withExample("9786041234567")
                    .withHint("ISBN của đầu sách ĐÃ có trong danh mục (nhập đầu sách trước). Mỗi đầu sách chỉ là một mặt hàng"),
            ColumnSpec.of("sellingPrice", "Giá bán (VND)", false, ColumnType.MONEY).withExample("85000")
                    .withHint("Số nguyên đồng, không âm. Bỏ trống thì dùng giá bìa"),
            ColumnSpec.of("lowStockThreshold", "Ngưỡng tồn thấp", false, ColumnType.INTEGER).withExample("5")
                    .withHint("Số nguyên không âm. Bỏ trống thì dùng ngưỡng của cửa hàng"),
            ColumnSpec.text("sku", "SKU", false, StockItemDetails.MAX_SKU).withExample("SACH-0001")
                    .withHint("Tối đa 40 ký tự, tự đổi sang chữ hoa, không trùng mặt hàng khác"),
            ColumnSpec.text("supplier", "Nhà cung cấp", false, StockItemDetails.MAX_SUPPLIER).withExample("NXB Trẻ")
                    .withHint("Văn bản tự do, tối đa 255 ký tự (không phải liên kết tới danh sách nhà cung cấp)"),
            ColumnSpec.of("costPrice", "Giá nhập (VND)", false, ColumnType.MONEY).withExample("60000")
                    .withHint("Số nguyên đồng, không âm. Dữ liệu nội bộ"),
            ColumnSpec.text("binLocation", "Vị trí kho", false, StockItemDetails.MAX_BIN_LOCATION).withExample("Kệ A2"),
            ColumnSpec.of("reorderQuantity", "SL đặt lại", false, ColumnType.INTEGER).withExample("20")
                    .withHint("Số nguyên không âm"),
            ColumnSpec.text("note", "Ghi chú nội bộ", false, StockItemDetails.MAX_NOTE),
            ColumnSpec.of("openingQuantity", "Tồn đầu kỳ", false, ColumnType.INTEGER).withExample("30")
                    .withHint("Số nguyên không âm. Lớn hơn 0 thì ghi một biến động điều chỉnh vào sổ cái tồn kho")));

    private final CatalogLookupPort catalog;
    private final StockPort stock;
    private final StockItemImportExecutor executor;

    public StockItemImportHandler(CatalogLookupPort catalog, StockPort stock, StockItemImportExecutor executor) {
        this.catalog = catalog;
        this.stock = stock;
        this.executor = executor;
    }

    @Override
    public ImportSchema schema() {
        return SCHEMA;
    }

    @Override
    public List<ImportRow> validate(ImportContext ctx, List<ImportRow> rows) {
        int n = rows.size();
        ImportRow[] out = new ImportRow[n];
        StockItemImportRow[] parsed = new StockItemImportRow[n];

        // 1. Quy tắc nghiệp vụ theo domain + trùng trong file (dòng đầu tiên thắng)
        Set<String> seenIsbn = new HashSet<>();
        Set<String> seenSku = new HashSet<>();
        for (int i = 0; i < n; i++) {
            ImportRow row = rows.get(i);
            out[i] = row;
            if (row.status() == RowStatus.INVALID) {
                continue;
            }
            StockItemImportRow.Parsed p = StockItemImportRow.parse(row);
            if (!p.ok()) {
                ImportRow r = row;
                for (RowIssue issue : p.issues()) {
                    r = r.withIssue(issue);
                }
                out[i] = r;
                continue;
            }
            StockItemImportRow v = p.value();
            ImportRow r = row;
            if (!seenIsbn.add(v.isbn())) {
                r = r.invalid(RowIssue.error("isbn", IssueCodes.DUPLICATE_IN_FILE, "ISBN trùng với dòng khác trong file"));
            }
            String sku = v.details().sku();
            if (sku != null && !seenSku.add(sku)) {
                r = r.invalid(RowIssue.error("sku", IssueCodes.DUPLICATE_IN_FILE, "SKU trùng với dòng khác trong file"));
            }
            out[i] = r;
            if (r.status() == RowStatus.VALID) {
                parsed[i] = v;
            }
        }

        // 2. Tra ISBN -> đầu sách: MỘT lời gọi catalog theo lô (token người dùng của luồng request)
        Set<String> isbns = new HashSet<>();
        for (StockItemImportRow v : parsed) {
            if (v != null) {
                isbns.add(v.isbn());
            }
        }
        Map<String, CatalogLookupPort.CatalogTitle> titles = isbns.isEmpty() ? Map.of() : catalog.lookupByIsbn(isbns);

        // 3. Kiểm DB theo lô: đầu sách đã là mặt hàng, SKU đã dùng (mỗi loại MỘT truy vấn)
        Set<UUID> titleIds = new HashSet<>();
        Set<String> skus = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (parsed[i] == null) {
                continue;
            }
            CatalogLookupPort.CatalogTitle t = titles.get(parsed[i].isbn());
            if (t == null) {
                out[i] = out[i].invalid(RowIssue.error("isbn", TITLE_NOT_FOUND,
                        "Không có đầu sách với ISBN này trong danh mục"));
                parsed[i] = null;
                continue;
            }
            titleIds.add(t.id());
            if (parsed[i].details().sku() != null) {
                skus.add(parsed[i].details().sku());
            }
        }
        Set<UUID> stocked = titleIds.isEmpty() ? Set.of() : stock.titleIdsWithStockItem(ctx.tenantId(), titleIds);
        Set<String> usedSkus = skus.isEmpty() ? Set.of() : stock.existingSkus(ctx.tenantId(), skus);

        for (int i = 0; i < n; i++) {
            if (parsed[i] == null) {
                continue;
            }
            CatalogLookupPort.CatalogTitle t = titles.get(parsed[i].isbn());
            ImportRow r = out[i].withExtra(EXTRA_TITLE_ID, t.id().toString());
            if (t.coverPrice() != null) {
                r = r.withExtra(EXTRA_COVER_PRICE, t.coverPrice().toPlainString());
            }
            if (stocked.contains(t.id())) {
                r = r.duplicate(RowIssue.error("isbn", STOCK_ITEM_EXISTS, "Đầu sách đã là mặt hàng"));
            } else if (parsed[i].details().sku() != null && usedSkus.contains(parsed[i].details().sku())) {
                r = r.duplicate(RowIssue.error("sku", SKU_EXISTS, "SKU đã được dùng cho mặt hàng khác"));
            }
            out[i] = r;
        }
        return new ArrayList<>(Arrays.asList(out));
    }

    @Override
    public ChunkResult commitChunk(ImportContext ctx, List<ImportRow> chunk) {
        try {
            return ChunkResult.of(executor.commitRows(ctx, chunk));
        } catch (RuntimeException batchFailure) {
            // Ghi đồng thời (người khác vừa tạo cùng đầu sách/SKU) hỏng cả transaction: chạy lại từng dòng để chỉ dòng
            // vi phạm thành SKIPPED_DUPLICATE (ADR-0024 3.6 bước 3). Lỗi hạ tầng thật lặp lại ở từng dòng và ném ra.
            log.info("Import stock-items: chunk lỗi ({}), chạy lại từng dòng", batchFailure.getClass().getSimpleName());
            List<RowOutcome> outcomes = new ArrayList<>(chunk.size());
            for (ImportRow row : chunk) {
                try {
                    outcomes.addAll(executor.commitRows(ctx, List.of(row)));
                } catch (SalesException e) {
                    outcomes.add(outcomeOf(row, e));
                }
            }
            return ChunkResult.of(outcomes);
        }
    }

    static RowOutcome outcomeOf(ImportRow row, SalesException e) {
        if (e.code() == ErrorCode.STOCK_ITEM_EXISTS) {
            return RowOutcome.skipped(row, RowIssue.error("isbn", STOCK_ITEM_EXISTS, "Đầu sách đã là mặt hàng"));
        }
        if (e.code() == ErrorCode.SKU_EXISTS) {
            return RowOutcome.skipped(row, RowIssue.error("sku", SKU_EXISTS, "SKU đã được dùng cho mặt hàng khác"));
        }
        return RowOutcome.failed(row, RowIssue.error(null, e.code().name(), e.getMessage()));
    }

    /** Một bản ghi audit tóm tắt cho cả lần confirm (không ghi giá nhập/ghi chú). */
    @Override
    public void onCompleted(ImportContext ctx, ImportResult result) {
        executor.recordSummary(ctx, result);
    }
}
