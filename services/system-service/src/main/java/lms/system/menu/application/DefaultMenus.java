package lms.system.menu.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.system.menu.domain.MenuItem;

/**
 * Bộ menu mặc định (ADR-0009 mục 2.5, M3). Tenant chưa có dòng nào trong {@code menus} nhận bộ này (lọc theo module
 * và quyền như dữ liệu thường); {@code POST /internal/menus/defaults} chép nó thành dữ liệu của tenant. Phải khớp
 * seed V2 của tenant mặc định. Khóa quyền là khóa endpoint thật ({@code Controller/method}); đổi tên controller thì
 * sửa ở đây và ở migration mới, nếu không mục chỉ còn admin thấy. Khóa SALES theo đề xuất ở mục 5 của
 * {@code docs/architecture/sales-module-design.md}.
 */
public final class DefaultMenus {

    /** Một mục mặc định; {@code parentCode == null} là mục gốc, cha luôn đứng trước con trong {@link #ENTRIES}. */
    public record Entry(String code, String parentCode, String label, String path, String icon, int sortOrder,
                        String permissionKey, String module) {}

    public static final List<Entry> ENTRIES = List.of(
            new Entry("home", null, "Trang chủ", "/", null, 10, null, null),
            new Entry("catalog", null, "Sách", "/catalog", null, 20, null, null),
            new Entry("circulation", null, "Mượn trả", "/circulation", null, 30, null, "LENDING"),
            new Entry("admin", null, "Quản trị", "/admin", null, 40, "AccessControlController/listRoles", null),
            new Entry("sales", null, "Bán hàng", null, null, 50, null, "SALES"),
            new Entry("sales-pos", "sales", "Bán tại quầy", "/sales/pos", null, 10,
                    "PosOrderController/create", "SALES"),
            new Entry("sales-orders", "sales", "Đơn hàng", "/sales/orders", null, 20,
                    "SalesOrderController/search", "SALES"),
            new Entry("sales-stock", "sales", "Tồn kho", "/sales/stock", null, 30,
                    "StockItemController/search", "SALES"),
            new Entry("sales-receipts", "sales", "Nhập hàng", "/sales/receipts", null, 40,
                    "GoodsReceiptController/search", "SALES"),
            new Entry("sales-suppliers", "sales", "Nhà cung cấp", "/sales/suppliers", null, 50,
                    "SupplierController/search", "SALES"));

    private DefaultMenus() {}

    /** Id tất định từ {@code code} (FE có khóa ổn định giữa các lần gọi); tách theo tenant. */
    static UUID idOf(UUID tenantId, String code) {
        return UUID.nameUUIDFromBytes(("lms.menu.default:" + tenantId + ":" + code).getBytes(StandardCharsets.UTF_8));
    }

    /** Bộ mặc định dưới dạng mục của tenant (chưa lưu), dùng cho {@code /me} khi tenant chưa có dữ liệu. */
    public static List<MenuItem> itemsFor(UUID tenantId) {
        return ENTRIES.stream().map(e -> new MenuItem(idOf(tenantId, e.code()), tenantId,
                e.parentCode() == null ? null : idOf(tenantId, e.parentCode()), e.code(), e.label(), e.path(),
                e.icon(), e.sortOrder(), true, e.permissionKey(), 0L, Instant.EPOCH, Instant.EPOCH,
                e.module())).toList();
    }
}
