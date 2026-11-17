package lms.sales.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.common.csv.CsvWriter;
import lms.sales.reporting.domain.ReportRange;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.domain.StockItem;

/**
 * Báo cáo CSV tồn kho (ADR-0018 mục 2.3): chuẩn bị (đếm, trần dòng) tách khỏi ghi để controller đặt header trước byte
 * đầu tiên. Tên/ISBN tra catalog theo lô 100; tra lỗi thì để trống, vẫn xuất đủ dòng.
 */
public class StockCsvReportUseCase {

    static final int BATCH = 100;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final StockPort stock;
    private final CatalogLookupPort catalog;
    private final GetSalesSettingsUseCase settings;
    private final Clock clock;
    private final int maxRows;

    public StockCsvReportUseCase(StockPort stock, CatalogLookupPort catalog, GetSalesSettingsUseCase settings,
                                 Clock clock, int maxRows) {
        this.stock = stock;
        this.catalog = catalog;
        this.settings = settings;
        this.clock = clock;
        this.maxRows = maxRows;
    }

    public ReportPlan prepare(UUID tenantId, boolean lowStockOnly) {
        int threshold = settings.execute(tenantId).lowStockThreshold();
        long total = stock.listForReport(tenantId, lowStockOnly, threshold, 0, 1).total();
        LocalDate today = LocalDate.now(clock.withZone(ReportRange.ZONE));
        return new ReportPlan("stock", "stock-" + DAY.format(today) + ".csv", total,
                (int) Math.min(total, maxRows), total > maxRows);
    }

    /** Ghi tiêu đề và đúng plan.rows() dòng (hoặc ít hơn nếu dữ liệu vừa giảm); trả số dòng đã ghi. Không đóng writer. */
    public int write(UUID tenantId, boolean lowStockOnly, ReportPlan plan, CsvWriter csv) {
        csv.header("titleId", "titleName", "isbn", "onHand", "reserved", "available", "threshold", "lowStock",
                "sellingPrice");
        int threshold = settings.execute(tenantId).lowStockThreshold();
        int written = 0;
        for (int page = 0; written < plan.rows(); page++) {
            List<StockItem> items = stock.listForReport(tenantId, lowStockOnly, threshold, page, BATCH).items();
            if (items.isEmpty()) {
                break;
            }
            Map<UUID, CatalogTitle> titles = lookup(items.stream().map(StockItem::titleId).toList());
            for (StockItem i : items) {
                if (written >= plan.rows()) {
                    break;
                }
                CatalogTitle t = titles.get(i.titleId());
                Money price = i.sellingPrice() != null ? i.sellingPrice()
                        : (t != null && t.coverPrice() != null ? Money.fromCoverPrice(t.coverPrice()) : null);
                csv.text(i.titleId().toString()).text(t == null ? null : t.name()).text(t == null ? null : t.isbn())
                        .number(i.onHand()).number(i.reserved()).number(i.available())
                        .number(i.lowStockThreshold() != null ? i.lowStockThreshold() : threshold)
                        .bool(i.isLowStock(threshold));
                if (price == null) {
                    csv.empty();
                } else {
                    csv.number(price.amount());
                }
                csv.endRow();
                written++;
            }
        }
        csv.flush();
        return written;
    }

    private Map<UUID, CatalogTitle> lookup(List<UUID> ids) {
        try {
            return catalog.lookup(ids);
        } catch (SalesException e) {
            return Map.of();
        }
    }
}
