package lms.sales.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lms.sales.tenantdata.domain.ExcludedItem;
import lms.sales.tenantdata.domain.ExportColumn;
import lms.sales.tenantdata.domain.ExportTable;
import lms.sales.tenantdata.domain.SalesExportCatalog;
import org.junit.jupiter.api.Test;

/** Danh mục bảng xuất: tên hợp lệ, không trùng, luôn có tenant_id, không chứa cột bí mật. */
class SalesExportCatalogTest {

    @Test
    void tenBangHopLeKhongTrungVaMoiBangCoTenantId() {
        Set<String> names = new HashSet<>();
        for (ExportTable t : SalesExportCatalog.tables()) {
            assertThat(t.name()).matches("[a-z0-9_]+");
            assertThat(names.add(t.name())).as("trùng bảng " + t.name()).isTrue();
            assertThat(t.columns().stream().map(ExportColumn::name)).contains("tenant_id");
        }
        assertThat(names).contains("invoices", "invoice_lines", "invoice_requests", "invoice_settings",
                "cashier_shifts", "cashier_shift_events", "cashier_shift_settings", "sales_orders", "payment_records",
                "refunds", "sales_returns", "stock_ledger", "goods_receipts", "suppliers", "sales_counters");
    }

    @Test
    void khongXuatCotBiMatHoacBangToanCuc() {
        for (ExportTable t : SalesExportCatalog.tables()) {
            assertThat(t.columns().stream().map(ExportColumn::name)).as(t.name())
                    .doesNotContain("qr_payload", "request_hash", "secret", "password", "token");
        }
        assertThat(SalesExportCatalog.tables().stream().map(ExportTable::name)).doesNotContain("sales_tenants");
        List<String> excluded = SalesExportCatalog.excluded().stream().map(ExcludedItem::table).toList();
        assertThat(excluded).contains("sales_tenants", "payment_intents.qr_payload", "sales_orders.request_hash");
        assertThat(SalesExportCatalog.excluded()).allSatisfy(e -> assertThat(e.reason()).isNotBlank());
    }

    @Test
    void thongTinNguoiMuaVaKhachLeThuocDuLieuTenantNenDuocXuat() {
        assertThat(columns("sales_orders")).contains("walk_in_name", "walk_in_phone", "recipient_name",
                "recipient_phone", "recipient_address", "customer_note", "staff_note", "pii_anonymized_at");
        assertThat(columns("invoices")).contains("buyer_name", "buyer_company", "buyer_tax_code", "buyer_address",
                "buyer_email", "seller_tax_code", "invoice_no", "amount_in_words");
        assertThat(columns("invoice_requests")).contains("buyer_name", "buyer_email");
        assertThat(columns("shop_contact_requests")).contains("message", "contact_name", "contact_email",
                "contact_phone");
    }

    private static List<String> columns(String table) {
        return SalesExportCatalog.tables().stream().filter(t -> t.name().equals(table)).findFirst().orElseThrow()
                .columns().stream().map(ExportColumn::name).toList();
    }
}
