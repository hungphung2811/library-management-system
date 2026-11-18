package lms.sales.stock.application.imports;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import lms.imports.core.ImportRow;
import lms.imports.core.RowIssue;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.StockItemDetails;

/**
 * Dòng mặt hàng đã đọc thành kiểu nghiệp vụ. Dùng chung cho validate (lúc upload) và commit (luồng nền), nên cùng một
 * bộ quy tắc: giá trị lấy từ chuỗi của {@link ImportRow}, quy tắc kiểm do domain ({@link Money},
 * {@link StockItemDetails}) quyết định. Không log giá trị (giá nhập, ghi chú là dữ liệu nội bộ).
 */
record StockItemImportRow(String isbn, Long sellingPrice, Integer lowStockThreshold, StockItemDetails details,
                          int openingQuantity) {

    /** Kết quả đọc: hoặc có giá trị, hoặc danh sách lỗi theo cột. */
    record Parsed(StockItemImportRow value, List<RowIssue> issues) {
        boolean ok() {
            return issues.isEmpty();
        }
    }

    static Parsed parse(ImportRow row) {
        List<RowIssue> issues = new ArrayList<>();
        String isbn = row.value("isbn").trim();
        Long sellingPrice = money(row, "sellingPrice", issues);
        Integer low = nonNegativeInt(row, "lowStockThreshold", issues);
        Long cost = money(row, "costPrice", issues);
        Integer reorder = nonNegativeInt(row, "reorderQuantity", issues);
        Integer opening = nonNegativeInt(row, "openingQuantity", issues);
        StockItemDetails details = StockItemDetails.EMPTY;
        if (issues.isEmpty()) {
            try {
                details = StockItemDetails.of(blankToNull(row.value("sku")), blankToNull(row.value("supplier")), cost,
                        blankToNull(row.value("binLocation")), reorder, null, blankToNull(row.value("note")));
            } catch (SalesException e) {
                issues.add(RowIssue.error(null, "INVALID_DETAILS", e.getMessage()));
            }
        }
        if (!issues.isEmpty()) {
            return new Parsed(null, issues);
        }
        return new Parsed(new StockItemImportRow(isbn, sellingPrice, low, details, opening == null ? 0 : opening),
                List.of());
    }

    private static Long money(ImportRow row, String key, List<RowIssue> issues) {
        String v = row.value(key).trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(v);
            long amount = d.stripTrailingZeros().scale() > 0 ? -1 : d.longValueExact();
            if (amount < 0 && d.signum() >= 0) {
                issues.add(RowIssue.error(key, "INVALID_PRICE", "Phải là số nguyên đồng, không có phần lẻ"));
                return null;
            }
            Money.of(amount); // domain kiểm không âm
            return amount;
        } catch (ArithmeticException | NumberFormatException | SalesException e) {
            issues.add(RowIssue.error(key, "INVALID_PRICE", "Số tiền phải là số nguyên đồng, không âm"));
            return null;
        }
    }

    private static Integer nonNegativeInt(ImportRow row, String key, List<RowIssue> issues) {
        String v = row.value(key).trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            BigInteger n = new BigDecimal(v).toBigIntegerExact();
            if (n.signum() < 0 || n.bitLength() > 31) {
                issues.add(RowIssue.error(key, "INVALID_QUANTITY", "Phải là số nguyên không âm"));
                return null;
            }
            return n.intValue();
        } catch (ArithmeticException | NumberFormatException e) {
            issues.add(RowIssue.error(key, "INVALID_QUANTITY", "Phải là số nguyên không âm"));
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
