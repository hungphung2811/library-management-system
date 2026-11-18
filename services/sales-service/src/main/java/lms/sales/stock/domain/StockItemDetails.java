package lms.sales.stock.domain;

import java.time.Instant;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;

/**
 * Thông tin quản lý tùy chọn của mặt hàng (không ảnh hưởng bộ đếm tồn hay giá bán): SKU, nhà cung cấp, giá nhập, vị
 * trí kho, số lượng đặt lại gợi ý, lần nhập gần nhất, ghi chú nội bộ. Mọi trường có thể null. {@code costPrice} và
 * {@code note} là dữ liệu nội bộ: không bao giờ ra storefront và không ghi vào log/audit/event.
 */
public record StockItemDetails(String sku, String supplier, Money costPrice, String binLocation,
                               Integer reorderQuantity, Instant lastRestockedAt, String note) {

    public static final int MAX_SKU = 40;
    public static final int MAX_SUPPLIER = 255;
    public static final int MAX_BIN_LOCATION = 64;
    public static final int MAX_NOTE = 500;

    public static final StockItemDetails EMPTY = new StockItemDetails(null, null, null, null, null, null, null);

    /** Chuẩn hóa và kiểm: chuỗi rỗng thành null, SKU cắt khoảng trắng và viết hoa, kiểm độ dài/dấu. */
    public StockItemDetails {
        sku = limit(sku, MAX_SKU, "SKU");
        if (sku != null) {
            sku = sku.toUpperCase(java.util.Locale.ROOT);
        }
        supplier = limit(supplier, MAX_SUPPLIER, "Nhà cung cấp");
        binLocation = limit(binLocation, MAX_BIN_LOCATION, "Vị trí kho");
        note = limit(note, MAX_NOTE, "Ghi chú");
        if (reorderQuantity != null && reorderQuantity < 0) {
            throw SalesException.invalidQuantity("Số lượng đặt lại không được âm");
        }
    }

    /** Dựng từ giá nhập dạng số đồng (null = không có); âm thì ném INVALID_PRICE. */
    public static StockItemDetails of(String sku, String supplier, Long costPrice, String binLocation,
                                      Integer reorderQuantity, Instant lastRestockedAt, String note) {
        return new StockItemDetails(sku, supplier, costPrice == null ? null : Money.of(costPrice), binLocation,
                reorderQuantity, lastRestockedAt, note);
    }

    public StockItemDetails withLastRestockedAt(Instant at) {
        return new StockItemDetails(sku, supplier, costPrice, binLocation, reorderQuantity, at, note);
    }

    public StockItemDetails withoutSku() {
        return new StockItemDetails(null, supplier, costPrice, binLocation, reorderQuantity, lastRestockedAt, note);
    }

    /** Điền các trường còn null từ {@code other} (dùng cho seed cập nhật không ghi đè dữ liệu có sẵn). */
    public StockItemDetails fillMissingFrom(StockItemDetails other) {
        return new StockItemDetails(sku != null ? sku : other.sku, supplier != null ? supplier : other.supplier,
                costPrice != null ? costPrice : other.costPrice,
                binLocation != null ? binLocation : other.binLocation,
                reorderQuantity != null ? reorderQuantity : other.reorderQuantity,
                lastRestockedAt != null ? lastRestockedAt : other.lastRestockedAt, note != null ? note : other.note);
    }

    private static String limit(String value, int max, String label) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() > max) {
            throw SalesException.invalidRequest(label + " tối đa " + max + " ký tự");
        }
        return v;
    }

    /** Không in giá nhập và ghi chú ra log. */
    @Override
    public String toString() {
        return "StockItemDetails[sku=" + sku + "]";
    }
}
