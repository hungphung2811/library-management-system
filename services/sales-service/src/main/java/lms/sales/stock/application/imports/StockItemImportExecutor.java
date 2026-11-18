package lms.sales.stock.application.imports;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.RowIssue;
import lms.imports.core.RowOutcome;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.application.StockService;
import lms.sales.stock.domain.StockItem;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi một chunk mặt hàng trong MỘT transaction (bean riêng để đi qua proxy giao dịch, ADR-0024 3.6). Luồng nền đã được
 * đặt {@code TenantContext.runAs(tenant)} trước khi vào đây, userId lấy từ phiên.
 * <p>
 * Thứ tự: kiểm lại theo lô (đầu sách đã là mặt hàng, SKU đã dùng: mỗi loại MỘT truy vấn) -> chèn mặt hàng một lần (JDBC
 * batch) -> bản sao giá bìa -> tồn đầu kỳ bằng {@link StockService#adjust} (loại ADJUSTMENT, sổ cái khớp bộ đếm) theo
 * titleId TĂNG DẦN (cùng thứ tự khóa của StockService.applyOne, tránh deadlock với đơn đang bán).
 */
public class StockItemImportExecutor {

    private final StockPort stock;
    private final StockService stockService;
    private final SalesAuditPort audit;
    private final Clock clock;

    public StockItemImportExecutor(StockPort stock, StockService stockService, SalesAuditPort audit, Clock clock) {
        this.stock = stock;
        this.stockService = stockService;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public List<RowOutcome> commitRows(ImportContext ctx, List<ImportRow> rows) {
        UUID tenant = ctx.tenantId();
        UUID actor = parseUuid(ctx.userId());

        record Item(ImportRow row, UUID titleId, StockItemImportRow value) {
        }
        List<Item> items = new ArrayList<>(rows.size());
        Map<Integer, RowOutcome> outcomes = new LinkedHashMap<>();
        for (ImportRow row : rows) {
            StockItemImportRow.Parsed p = StockItemImportRow.parse(row);
            String titleId = row.extra().get(StockItemImportHandler.EXTRA_TITLE_ID);
            if (!p.ok() || titleId == null) {
                outcomes.put(row.rowNumber(), RowOutcome.failed(row,
                        RowIssue.error(null, "INVALID_ROW", "Dòng không còn hợp lệ khi ghi")));
                continue;
            }
            items.add(new Item(row, UUID.fromString(titleId), p.value()));
        }

        Set<UUID> stocked = stock.titleIdsWithStockItem(tenant, items.stream().map(Item::titleId).toList());
        Set<String> skus = new HashSet<>();
        for (Item i : items) {
            if (i.value().details().sku() != null) {
                skus.add(i.value().details().sku());
            }
        }
        Set<String> usedSkus = skus.isEmpty() ? Set.of() : stock.existingSkus(tenant, skus);

        List<StockItem> toInsert = new ArrayList<>();
        Map<UUID, Long> covers = new HashMap<>();
        Map<UUID, Integer> openings = new TreeMap<>(); // titleId tăng dần
        Set<UUID> seenTitles = new HashSet<>();
        Set<String> seenSkus = new HashSet<>();
        for (Item i : items) {
            String sku = i.value().details().sku();
            if (stocked.contains(i.titleId()) || !seenTitles.add(i.titleId())) {
                outcomes.put(i.row().rowNumber(), RowOutcome.skipped(i.row(), RowIssue.error("isbn",
                        StockItemImportHandler.STOCK_ITEM_EXISTS, "Đầu sách đã là mặt hàng")));
                continue;
            }
            if (sku != null && (usedSkus.contains(sku) || !seenSkus.add(sku))) {
                outcomes.put(i.row().rowNumber(), RowOutcome.skipped(i.row(), RowIssue.error("sku",
                        StockItemImportHandler.SKU_EXISTS, "SKU đã được dùng cho mặt hàng khác")));
                continue;
            }
            toInsert.add(StockItem.register(tenant, i.titleId(),
                    i.value().sellingPrice() == null ? null : Money.of(i.value().sellingPrice()),
                    i.value().lowStockThreshold(), i.value().details()));
            Long cover = coverPrice(i.row());
            if (cover != null) {
                covers.put(i.titleId(), cover);
            }
            if (i.value().openingQuantity() > 0) {
                openings.put(i.titleId(), i.value().openingQuantity());
            }
            outcomes.put(i.row().rowNumber(), RowOutcome.created(i.row()));
        }

        stock.insertAll(toInsert);
        stock.refreshCoverPrices(tenant, covers);
        String reason = "Tồn đầu kỳ nhập từ Excel " + ctx.importId();
        for (Map.Entry<UUID, Integer> e : openings.entrySet()) {
            stockService.adjust(tenant, actor, e.getKey(), e.getValue(), reason);
        }

        List<RowOutcome> ordered = new ArrayList<>(rows.size());
        for (ImportRow row : rows) {
            ordered.add(outcomes.get(row.rowNumber()));
        }
        return ordered;
    }

    /** Audit tóm tắt: một bản ghi cho cả lần confirm (không giá nhập, không ghi chú). */
    @Transactional
    public void recordSummary(ImportContext ctx, ImportResult result) {
        audit.record(new SalesAuditPort.AuditEntry(ctx.tenantId(), SalesAuditPort.Actions.STOCK_ITEMS_IMPORTED,
                "StockItemImport", ctx.importId(), parseUuid(ctx.userId()), clock.instant(), Map.of(
                "importId", String.valueOf(result.importId()), "created", result.created(),
                "skippedDuplicate", result.skippedDuplicate(), "failed", result.failed(),
                "completed", result.completed())));
    }

    private static Long coverPrice(ImportRow row) {
        String v = row.extra().get(StockItemImportHandler.EXTRA_COVER_PRICE);
        if (v == null) {
            return null;
        }
        try {
            return Money.fromCoverPrice(new BigDecimal(v)).amount();
        } catch (SalesException | NumberFormatException e) {
            return null; // giá catalog không hợp lệ: không có bản sao giá bìa, đồng bộ lười sau
        }
    }

    static UUID parseUuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
