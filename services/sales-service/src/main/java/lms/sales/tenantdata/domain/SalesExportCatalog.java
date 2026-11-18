package lms.sales.tenantdata.domain;

import static lms.sales.tenantdata.domain.ExportTable.table;

import java.util.ArrayList;
import java.util.List;

/**
 * Danh sách cố định các bảng sales_db được xuất cho gói dữ liệu tenant (ADR-0031 F1) và những gì bị loại có chủ đích.
 * Không dump schema thô: thêm cột/bảng mới vào DB không tự động ra gói xuất. Test IT đối chiếu với
 * {@code information_schema} để mọi cột phải thuộc danh sách xuất hoặc danh sách loại (chống quên khi schema đổi).
 *
 * <p>Nguyên tắc: tenant là chủ dữ liệu nên thông tin người mua/người nhận/khách lẻ và chứng từ (hóa đơn, phiếu thu) được
 * xuất đầy đủ, đúng như đang lưu (dữ liệu đã bị ẩn danh thì xuất giá trị rỗng đã ẩn danh, không có bản gốc). Chỉ loại bí
 * mật, khóa/băm kỹ thuật và bảng toàn cục không thuộc tenant.
 */
public final class SalesExportCatalog {

    public static final String SERVICE = "sales";
    public static final int SCHEMA_VERSION = 1;

    private static final List<ExportTable> TABLES = List.of(
            // Cấu hình
            table("sales_settings", "tenant_id", "tenant_id:uuid", "vat_rate_bps:num", "prices_include_vat:bool",
                    "return_window_days:num", "fixed_shipping_fee:num", "low_stock_threshold:num",
                    "walk_in_pii_retention_months:num", "currency:text", "version:num", "updated_at:ts",
                    "updated_by:uuid").build(),
            table("order_abuse_policies", "tenant_id", "tenant_id:uuid", "reservation_minutes:num",
                    "max_pending_orders:num", "max_orders_per_day:num", "max_quantity_per_line:num",
                    "max_total_quantity:num", "orders_per_minute_per_user:num", "orders_per_minute_tenant:num",
                    "version:num", "updated_at:ts", "updated_by:uuid").build(),
            table("invoice_settings", "tenant_id", "tenant_id:uuid", "seller_legal_name:text", "seller_tax_code:text",
                    "seller_address:text", "seller_phone:text", "seller_email:text", "template_code:text",
                    "series:text", "auto_issue_on_completion:bool", "version:num", "updated_at:ts",
                    "updated_by:uuid").build(),
            table("cashier_shift_settings", "tenant_id", "tenant_id:uuid", "require_open_shift_for_pos:bool",
                    "variance_threshold:num", "version:num", "updated_at:ts", "updated_by:uuid").build(),
            table("sales_counters", "name", "tenant_id:uuid", "name:text", "last_value:num").build(),
            // Mặt hàng, tồn kho, nhập hàng
            table("stock_items", "id", "id:uuid", "tenant_id:uuid", "title_id:uuid", "work_id:uuid", "sku:text",
                    "on_hand:num", "reserved:num", "selling_price:num", "cover_price_snapshot:num",
                    "effective_price:num", "cost_price:num", "low_stock_threshold:num", "reorder_quantity:num",
                    "supplier:text", "bin_location:text", "note:text", "active:bool", "last_restocked_at:ts",
                    "created_at:ts", "updated_at:ts").build(),
            table("stock_ledger", "id", "id:uuid", "tenant_id:uuid", "stock_item_id:uuid", "type:text",
                    "delta_on_hand:num", "delta_reserved:num", "ref_type:text", "ref_id:uuid", "reason:text",
                    "actor_id:uuid", "occurred_at:ts").build(),
            table("suppliers", "id", "id:uuid", "tenant_id:uuid", "name:text", "phone:text", "email:text",
                    "address:text", "note:text", "active:bool", "created_at:ts", "updated_at:ts").build(),
            table("goods_receipts", "id", "id:uuid", "tenant_id:uuid", "receipt_no:text", "supplier_id:uuid",
                    "status:text", "client_request_id:uuid", "created_by:uuid", "created_at:ts", "confirmed_at:ts",
                    "confirmed_by:uuid", "version:num").build(),
            table("goods_receipt_lines", "id", "id:uuid", "tenant_id:uuid", "receipt_id:uuid", "line_no:num",
                    "title_id:uuid", "quantity:num", "unit_cost:num").build(),
            // Đơn hàng, thanh toán, hoàn tiền, trả hàng
            table("sales_orders", "id", "id:uuid", "tenant_id:uuid", "order_no:text", "channel:text", "status:text",
                    "payment_status:text", "payment_method:text", "customer_user_id:uuid", "walk_in_name:text",
                    "walk_in_phone:text", "fulfilment:text", "recipient_name:text", "recipient_phone:text",
                    "recipient_address:text", "customer_note:text", "staff_note:text", "expected_delivery_date:date",
                    "vat_rate_bps:num", "prices_include_vat:bool", "currency:text", "subtotal:num",
                    "discount_total:num", "vat_total:num", "shipping_fee:num", "grand_total:num",
                    "order_discount_type:text", "order_discount_value:num", "client_request_id:uuid",
                    "exchange_of_return_id:uuid", "reservation_expires_at:ts", "created_by:uuid", "created_at:ts",
                    "confirmed_at:ts", "confirmed_by:uuid", "paid_at:ts", "completed_at:ts", "cancelled_at:ts",
                    "cancelled_by:uuid", "cancel_reason:text", "pii_anonymized_at:ts", "version:num")
                    .exclude("request_hash", "băm kỹ thuật chống đặt trùng, không phải dữ liệu nghiệp vụ").build(),
            table("sales_order_lines", "id", "id:uuid", "tenant_id:uuid", "order_id:uuid", "line_no:num",
                    "title_id:uuid", "title_name:text", "isbn:text", "quantity:num", "unit_price:num",
                    "line_discount:num", "allocated_order_discount:num", "amount:num", "vat_amount:num",
                    "line_total:num").build(),
            table("payment_records", "id", "id:uuid", "tenant_id:uuid", "order_id:uuid", "shift_id:uuid", "amount:num",
                    "method:text", "reference:text", "recorded_by:uuid", "recorded_at:ts").build(),
            table("payment_intents", "id", "id:uuid", "tenant_id:uuid", "order_id:uuid", "shift_id:uuid", "amount:num",
                    "reference:text", "status:text", "provider:text", "provider_txn_id:text", "transaction_ref:text",
                    "tendered_amount:num", "change_due:num", "expires_at:ts", "paid_at:ts", "created_at:ts",
                    "version:num")
                    .exclude("qr_payload", "nội dung mã QR thanh toán (dữ liệu của cổng thanh toán, ADR-0031 D2)")
                    .build(),
            table("refunds", "id", "id:uuid", "tenant_id:uuid", "order_id:uuid", "shift_id:uuid", "source:text",
                    "return_id:uuid", "amount:num", "status:text", "method:text", "reference:text", "created_at:ts",
                    "refunded_by:uuid", "refunded_at:ts", "version:num").build(),
            table("sales_returns", "id", "id:uuid", "tenant_id:uuid", "return_no:text", "order_id:uuid", "reason:text",
                    "late:bool", "late_reason:text", "days_overdue:num", "refund_total:num", "client_request_id:uuid",
                    "created_by:uuid", "created_at:ts").build(),
            table("sales_return_lines", "id", "id:uuid", "tenant_id:uuid", "return_id:uuid", "order_line_id:uuid",
                    "quantity:num", "disposition:text", "refund_amount:num").build(),
            // Hóa đơn VAT (chứng từ, xuất đầy đủ)
            table("invoices", "id", "id:uuid", "tenant_id:uuid", "kind:text", "status:text", "order_id:uuid",
                    "order_no:text", "return_id:uuid", "original_invoice_id:uuid", "template_code:text", "series:text",
                    "fiscal_year:num", "invoice_no:num", "issued_at:ts", "issued_by:uuid", "cancelled_at:ts",
                    "cancelled_by:uuid", "cancel_reason:text", "seller_legal_name:text", "seller_tax_code:text",
                    "seller_address:text", "seller_phone:text", "seller_email:text", "buyer_name:text",
                    "buyer_company:text", "buyer_tax_code:text", "buyer_address:text", "buyer_email:text",
                    "currency:text", "prices_include_vat:bool", "payment_method:text", "total_before_vat:num",
                    "vat_total:num", "grand_total:num", "amount_in_words:text", "issuer:text", "external_id:text",
                    "external_status:text", "external_lookup_code:text", "client_request_id:uuid", "created_by:uuid",
                    "created_at:ts", "version:num").build(),
            table("invoice_lines", "id", "id:uuid", "tenant_id:uuid", "invoice_id:uuid", "line_no:num",
                    "order_line_id:uuid", "item_name:text", "isbn:text", "unit:text", "quantity:num", "unit_price:num",
                    "discount:num", "amount_before_vat:num", "vat_rate_bps:num", "vat_amount:num", "line_total:num")
                    .build(),
            table("invoice_requests", "id", "id:uuid", "tenant_id:uuid", "order_id:uuid", "buyer_name:text",
                    "buyer_company:text", "buyer_tax_code:text", "buyer_address:text", "buyer_email:text",
                    "source:text", "status:text", "requested_by:uuid", "created_at:ts", "updated_at:ts",
                    "pii_anonymized_at:ts", "version:num").build(),
            // Ca thu ngân
            table("cashier_shifts", "id", "id:uuid", "tenant_id:uuid", "shift_no:text", "cashier_user_id:uuid",
                    "register_label:text", "status:text", "opened_at:ts", "opening_float:num", "closed_at:ts",
                    "closed_by:uuid", "counted_cash:num", "expected_cash:num", "variance:num", "variance_reason:text",
                    "variance_status:text", "approved_by:uuid", "approved_at:ts", "approval_note:text",
                    "reopen_count:num", "client_request_id:uuid", "version:num").build(),
            table("cashier_shift_events", "id", "id:uuid", "tenant_id:uuid", "shift_id:uuid", "type:text",
                    "actor_id:uuid", "occurred_at:ts", "snapshot:json").build(),
            // Cửa hàng trực tuyến
            table("shop_contact_requests", "id", "id:uuid", "tenant_id:uuid", "customer_user_id:uuid", "topic:text",
                    "message:text", "contact_name:text", "contact_email:text", "contact_phone:text",
                    "consent_at:ts", "status:text", "created_at:ts", "closed_at:ts", "pii_anonymized_at:ts",
                    "version:num").build(),
            table("storefront_banners", "id", "id:uuid", "tenant_id:uuid", "title:text", "subtitle:text",
                    "link_path:text", "sort_order:num", "active:bool", "starts_at:ts", "ends_at:ts", "version:num",
                    "created_at:ts", "updated_at:ts", "created_by:uuid", "updated_by:uuid").build(),
            // Nhật ký nghiệp vụ (không chứa PII)
            table("sales_audit_log", "id", "id:uuid", "tenant_id:uuid", "action:text", "aggregate_type:text",
                    "aggregate_id:uuid", "actor_id:uuid", "occurred_at:ts", "data:json", "published_at:ts").build());

    private static final List<ExcludedItem> EXCLUDED_TABLES = List.of(
            new ExcludedItem("sales_tenants",
                    "danh bạ tenant toàn cục của job ẩn danh (không RLS, không phải dữ liệu của tenant)"));

    private SalesExportCatalog() {
    }

    public static List<ExportTable> tables() {
        return TABLES;
    }

    /** Bảng và cột bị loại có chủ đích, theo thứ tự bảng. */
    public static List<ExcludedItem> excluded() {
        List<ExcludedItem> all = new ArrayList<>(EXCLUDED_TABLES);
        for (ExportTable t : TABLES) {
            t.excludedColumns().forEach((col, reason) -> all.add(new ExcludedItem(t.name() + "." + col, reason)));
        }
        return List.copyOf(all);
    }
}
