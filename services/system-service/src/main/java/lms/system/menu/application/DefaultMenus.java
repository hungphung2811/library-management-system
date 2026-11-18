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
            new Entry("catalog-barcode", "catalog", "Tra mã vạch", "/catalog/barcode", null, 10,
                    "BookCopyController/getByBarcode", null),
            new Entry("circulation", null, "Mượn trả", "/circulation", null, 30, null, "LENDING"),
            new Entry("circulation-borrow", "circulation", "Lập phiếu mượn", "/circulation/borrow", null, 10,
                    "BorrowingController/borrow", "LENDING"),
            new Entry("circulation-return", "circulation", "Nhận trả sách", "/circulation/return", null, 20,
                    "BorrowingController/returnBook", "LENDING"),
            new Entry("circulation-mine", "circulation", "Phiếu của tôi", "/circulation/mine", null, 30,
                    null, "LENDING"),
            new Entry("circulation-borrowings", "circulation", "Tra phiếu mượn", "/circulation/borrowings", null, 40,
                    "BorrowingController/search", "LENDING"),
            new Entry("circulation-reservations", "circulation", "Đặt trước", "/circulation/reservations", null, 50,
                    "ReservationController/mine", "LENDING"),
            new Entry("circulation-fines", "circulation", "Phạt", "/circulation/fines", null, 60,
                    "FineController/byReader", "LENDING"),
            new Entry("admin", null, "Quản trị", "/admin", null, 40, "AccessControlController/listRoles", null),
            new Entry("admin-users", "admin", "Người dùng", "/admin/users", null, 10, "UserController/search", null),
            new Entry("admin-roles", "admin", "Vai trò và quyền", "/admin/roles", null, 20,
                    "AccessControlController/listRoles", null),
            new Entry("admin-departments", "admin", "Phòng ban", "/admin/departments", null, 30,
                    "DepartmentController/tree", null),
            new Entry("admin-audit-logs", "admin", "Nhật ký hoạt động", "/admin/audit-logs", null, 40,
                    "AuditLogController/search", null),
            new Entry("admin-menus", "admin", "Menu", "/admin/menus", null, 50, "MenuAdminController/listMenus", null),
            new Entry("admin-contract", "admin", "Hợp đồng", "/admin/contract", null, 60,
                    "TenantContractController/current", null),
            new Entry("admin-storefront-origins", "admin", "Website", "/admin/storefront-origins", null, 70,
                    "TenantStorefrontOriginController/list", "SALES"),
            new Entry("sales", null, "Bán hàng", null, null, 50, null, "SALES"),
            new Entry("sales-pos", "sales", "Bán tại quầy", "/sales/pos", null, 10,
                    "PosOrderController/create", "SALES"),
            new Entry("sales-orders", "sales", "Đơn hàng", "/sales/orders", null, 20,
                    "SalesOrderController/search", "SALES"),
            new Entry("sales-contacts", "sales", "Liên hệ khách", "/sales/contacts", null, 25,
                    "SalesContactController/search", "SALES"),
            new Entry("sales-refunds", "sales", "Hoàn tiền", "/sales/refunds", null, 26,
                    "RefundController/search", "SALES"),
            new Entry("sales-stock", "sales", "Tồn kho", "/sales/stock", null, 30,
                    "StockItemController/search", "SALES"),
            new Entry("sales-receipts", "sales", "Nhập hàng", "/sales/receipts", null, 40,
                    "GoodsReceiptController/search", "SALES"),
            new Entry("sales-suppliers", "sales", "Nhà cung cấp", "/sales/suppliers", null, 50,
                    "SupplierController/search", "SALES"),
            new Entry("sales-audit", "sales", "Nhật ký bán hàng", "/sales/audit", null, 60,
                    "SalesAuditController/search", "SALES"),
            new Entry("sales-settings", "sales", "Cấu hình bán hàng", "/sales/settings", null, 70,
                    "SalesSettingsController/get", "SALES"));

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
