package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import lms.sales.tenantdata.application.ExportSalesTenantDataUseCase;
import lms.sales.tenantdata.domain.ExcludedItem;
import lms.sales.tenantdata.domain.ExportColumn;
import lms.sales.tenantdata.domain.ExportTable;
import lms.sales.tenantdata.domain.SalesExportCatalog;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0031 F1, cần Docker. Chạy bằng: mvn -pl services/sales-service -am test -Dtest=TenantDataExportIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44.
 * Postgres thật với vai trò {@code sales_app} (chịu RLS), hai tenant có dữ liệu ở MỌI bảng của sales_db (kể cả hóa đơn,
 * ca thu ngân): export của tenant A chỉ chứa dòng của A, số dòng khớp COUNT(*), không có cột bí mật, mọi cột của schema
 * thật phải thuộc danh sách xuất hoặc danh sách loại (bắt trường hợp thêm cột mới mà quên rà), phân trang nhiều lô đúng.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false",
        "lms.tenant-data.export.batch-size=3"})
@Testcontainers
class TenantDataExportIT {

    private static final UUID A = tenant(1);
    private static final UUID B = tenant(2);
    private static final UUID EMPTY = tenant(3);
    private static final int LEDGER_ROWS = 8; // > 2 lô với batch-size=3

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Autowired ExportSalesTenantDataUseCase useCase;
    @Autowired DataSource dataSource;

    private static UUID tenant(int t) {
        return id(t, 0);
    }

    /** Id xác định: tiền tố là số tenant nên dễ kiểm không lẫn dòng của tenant khác. */
    private static UUID id(int t, int n) {
        return UUID.fromString(String.format("%08d-0000-0000-0000-%012d", t, n));
    }

    private static String u(int t, int n) {
        return "'" + id(t, n) + "'";
    }

    private void seed(int t) throws Exception {
        String T = u(t, 0);
        List<String> sql = new ArrayList<>(List.of(
                "insert into sales_tenants(tenant_id) values (" + T + ")",
                "insert into sales_settings(tenant_id) values (" + T + ")",
                "insert into order_abuse_policies(tenant_id,reservation_minutes,max_pending_orders,max_orders_per_day,"
                        + "max_quantity_per_line,max_total_quantity,orders_per_minute_per_user,orders_per_minute_tenant)"
                        + " values (" + T + ",30,5,50,10,50,5,100)",
                "insert into invoice_settings(tenant_id,seller_legal_name,seller_tax_code,seller_address,template_code,"
                        + "series) values (" + T + ",'Cty \"Sách\", Việt','0123456789','1 Lê Lợi','1','C26T')",
                "insert into cashier_shift_settings(tenant_id) values (" + T + ")",
                "insert into sales_counters(tenant_id,name,last_value) values (" + T + ",'ORDER',5),(" + T
                        + ",'INVOICE:1:C26T:2026',1)",
                "insert into stock_items(id,tenant_id,title_id,on_hand,reserved,selling_price,note) values ("
                        + u(t, 1) + "," + T + "," + u(t, 2) + ",10,0,100000,'ghi chú')",
                "insert into suppliers(id,tenant_id,name) values (" + u(t, 3) + "," + T + ",'NCC')",
                "insert into goods_receipts(id,tenant_id,receipt_no,supplier_id,status,client_request_id) values ("
                        + u(t, 4) + "," + T + ",'PN1'," + u(t, 3) + ",'DRAFT'," + u(t, 5) + ")",
                "insert into goods_receipt_lines(id,tenant_id,receipt_id,line_no,title_id,quantity,unit_cost) values ("
                        + u(t, 6) + "," + T + "," + u(t, 4) + ",1," + u(t, 2) + ",3,50000)",
                "insert into sales_orders(id,tenant_id,order_no,channel,status,payment_method,walk_in_name,"
                        + "walk_in_phone,vat_rate_bps,prices_include_vat,subtotal,vat_total,grand_total,"
                        + "client_request_id,request_hash) values (" + u(t, 10) + "," + T + ",'DH1','POS','COMPLETED',"
                        + "'CASH','Nguyễn Văn Ánh','0901234567',500,true,100000,5000,100000," + u(t, 11)
                        + ",'HASH-" + t + "')",
                "insert into sales_order_lines(id,tenant_id,order_id,line_no,title_id,title_name,quantity,unit_price,"
                        + "amount,vat_amount,line_total) values (" + u(t, 12) + "," + T + "," + u(t, 10) + ",1,"
                        + u(t, 2) + ",'Sách A',1,100000,95000,5000,100000)",
                "insert into cashier_shifts(id,tenant_id,shift_no,cashier_user_id,status,opened_at,opening_float,"
                        + "client_request_id) values (" + u(t, 40) + "," + T + ",'CA1'," + u(t, 41) + ",'OPEN',now(),"
                        + "500000," + u(t, 42) + ")",
                "insert into cashier_shift_events(id,tenant_id,shift_id,type,occurred_at,snapshot) values (" + u(t, 43)
                        + "," + T + "," + u(t, 40) + ",'OPENED',now(),'{\"float\":500000}')",
                "insert into payment_records(id,tenant_id,order_id,shift_id,amount,method) values (" + u(t, 13) + ","
                        + T + "," + u(t, 10) + "," + u(t, 40) + ",100000,'CASH')",
                "insert into payment_intents(id,tenant_id,order_id,amount,reference,status,provider,qr_payload,"
                        + "expires_at) values (" + u(t, 14) + "," + T + "," + u(t, 10) + ",100000,'REF" + t
                        + "','PENDING','SEPAY','SECRET-QR-" + t + "',now())",
                "insert into sales_returns(id,tenant_id,return_no,order_id,reason,refund_total,client_request_id) values ("
                        + u(t, 15) + "," + T + ",'TH1'," + u(t, 10) + ",'lỗi in',100000," + u(t, 16) + ")",
                "insert into sales_return_lines(id,tenant_id,return_id,order_line_id,quantity,disposition,"
                        + "refund_amount) values (" + u(t, 17) + "," + T + "," + u(t, 15) + "," + u(t, 12)
                        + ",1,'RESTOCK',100000)",
                "insert into refunds(id,tenant_id,order_id,source,return_id,amount) values (" + u(t, 18) + "," + T + ","
                        + u(t, 10) + ",'RETURN'," + u(t, 15) + ",100000)",
                "insert into invoices(id,tenant_id,kind,status,order_id,order_no,template_code,series,"
                        + "seller_legal_name,seller_tax_code,seller_address,buyer_name,buyer_tax_code,"
                        + "prices_include_vat,total_before_vat,vat_total,grand_total) values (" + u(t, 20) + "," + T
                        + ",'ORIGINAL','DRAFT'," + u(t, 10) + ",'DH1','1','C26T','Cty','0123456789','1 Lê Lợi',"
                        + "'Trần Thị B','0312345678',true,95000,5000,100000)",
                "insert into invoice_lines(id,tenant_id,invoice_id,line_no,item_name,unit,quantity,unit_price,"
                        + "amount_before_vat,vat_rate_bps,vat_amount,line_total) values (" + u(t, 21) + "," + T + "," + u(t, 20)
                        + ",1,'Sách A','cuốn',1,100000,95000,500,5000,100000)",
                "insert into invoice_requests(id,tenant_id,order_id,source,status,buyer_name) values (" + u(t, 22) + ","
                        + T + "," + u(t, 10) + ",'POS','OPEN','Lê C')",
                "insert into shop_contact_requests(id,tenant_id,customer_user_id,topic,message,contact_name,"
                        + "contact_email,consent_at,status,created_at) values (" + u(t, 30) + "," + T + "," + u(t, 31)
                        + ",'ORDER','=1+1, \"nguy hiểm\"','Phạm D','d@example.com',now(),'NEW',now())",
                "insert into storefront_banners(id,tenant_id,title,created_at,updated_at) values (" + u(t, 32) + ","
                        + T + ",'Khuyến mãi',now(),now())",
                "insert into sales_audit_log(id,tenant_id,action,aggregate_type,occurred_at,data) values (" + u(t, 33)
                        + "," + T + ",'ORDER_COMPLETED','ORDER',now(),'{\"orderNo\":\"DH1\"}')"));
        for (int i = 0; i < LEDGER_ROWS; i++) {
            sql.add("insert into stock_ledger(id,tenant_id,stock_item_id,type,delta_on_hand) values (" + u(t, 100 + i)
                    + "," + T + "," + u(t, 1) + ",'RECEIPT',1)");
        }
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            for (String stmt : sql) {
                s.execute(stmt);
            }
        }
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            List<String> tables = new ArrayList<>(SalesExportCatalog.tables().stream().map(ExportTable::name).toList());
            tables.add("sales_tenants");
            s.execute("TRUNCATE " + String.join(", ", tables) + " CASCADE");
        }
        seed(1);
        seed(2);
    }

    private Map<String, byte[]> export(UUID tenant) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantContext.set(tenant);
        try {
            useCase.export(tenant, out);
        } finally {
            TenantContext.clear();
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                entries.put(e.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private long count(String table, UUID tenant) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from " + table + " where tenant_id = '" + tenant + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(byte[] csv) {
        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        return new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
    }

    /** Bộ tách CSV RFC 4180 tối thiểu. */
    private static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else {
                cell.append(c);
            }
        }
        return rows;
    }

    @Test
    void exportChiCoDongCuaTenantDichDuMoiBangVaSoDongKhopCount() throws Exception {
        for (UUID tenant : List.of(A, B)) {
            int idx = tenant.equals(A) ? 1 : 2;
            String foreignPrefix = String.format("%08d-0000-0000-0000", idx == 1 ? 2 : 1);
            Map<String, byte[]> zip = export(tenant);
            Set<String> expected = new HashSet<>();
            SalesExportCatalog.tables().forEach(t -> expected.add(t.file()));
            expected.add("_meta.json");
            assertThat(zip.keySet()).containsExactlyInAnyOrderElementsOf(expected);

            String meta = new String(zip.get("_meta.json"), StandardCharsets.UTF_8);
            for (ExportTable t : SalesExportCatalog.tables()) {
                String csv = text(zip.get(t.file()));
                List<List<String>> rows = parse(csv);
                List<String> header = rows.get(0);
                assertThat(header).as(t.name()).isEqualTo(t.columns().stream().map(ExportColumn::name).toList());
                int tenantCol = header.indexOf("tenant_id");
                long expectedRows = count(t.name(), tenant);
                assertThat(expectedRows).as("seed phải có dữ liệu ở " + t.name()).isGreaterThan(0);
                assertThat(rows.size() - 1).as("số dòng " + t.name()).isEqualTo((int) expectedRows);
                for (List<String> row : rows.subList(1, rows.size())) {
                    assertThat(row).hasSameSizeAs(header);
                    assertThat(row.get(tenantCol)).as(t.name()).isEqualTo(tenant.toString());
                }
                assertThat(csv).as("dòng của tenant khác lọt vào " + t.name()).doesNotContain(foreignPrefix);
                assertThat(meta).contains("\"name\":\"" + t.name() + "\",\"file\":\"" + t.file() + "\",\"rows\":"
                        + expectedRows + ",");
            }
            assertThat(parse(text(zip.get("stock_ledger.csv")))).hasSize(LEDGER_ROWS + 1);
        }
    }

    @Test
    void khongXuatBiMatBangToanCucVaGiuDungDuLieuNguoiMua() throws Exception {
        Map<String, byte[]> zip = export(A);
        assertThat(zip).doesNotContainKey("sales_tenants.csv");
        String all = zip.values().stream().map(b -> new String(b, StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertThat(all).doesNotContain("SECRET-QR").doesNotContain("HASH-1");
        assertThat(text(zip.get("payment_intents.csv"))).startsWith("id,tenant_id,").doesNotContain("qr_payload");
        assertThat(text(zip.get("sales_orders.csv"))).doesNotContain("request_hash");
        // thông tin người mua/khách lẻ là dữ liệu của tenant: xuất đầy đủ
        assertThat(text(zip.get("sales_orders.csv"))).contains("Nguyễn Văn Ánh", "0901234567");
        assertThat(text(zip.get("invoices.csv"))).contains("Trần Thị B", "0312345678", "0123456789");
        assertThat(text(zip.get("invoice_settings.csv"))).contains("\"Cty \"\"Sách\"\", Việt\"");
        // chống công thức + escape dấu phẩy/nháy kép
        assertThat(text(zip.get("shop_contact_requests.csv"))).contains("\"'=1+1, \"\"nguy hiểm\"\"\"");
        assertThat(new String(zip.get("_meta.json"), StandardCharsets.UTF_8))
                .contains("\"table\":\"sales_tenants\"").contains("\"table\":\"payment_intents.qr_payload\"")
                .contains("\"table\":\"sales_orders.request_hash\"");
    }

    @Test
    void moiCotCuaSchemaThatPhaiThuocDanhSachXuatHoacDanhSachLoai() throws Exception {
        Map<String, Set<String>> columnsByTable = new HashMap<>();
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select table_name, column_name from information_schema.columns "
                     + "where table_schema = 'public' and table_name <> 'flyway_schema_history'")) {
            while (rs.next()) {
                columnsByTable.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
            }
        }
        Set<String> excludedTables = new HashSet<>();
        for (ExcludedItem e : SalesExportCatalog.excluded()) {
            if (!e.table().contains(".")) {
                excludedTables.add(e.table());
            }
        }
        Set<String> exportedTables = new HashSet<>();
        for (ExportTable t : SalesExportCatalog.tables()) {
            exportedTables.add(t.name());
            Set<String> real = columnsByTable.get(t.name());
            assertThat(real).as("bảng " + t.name() + " phải tồn tại").isNotNull();
            Set<String> covered = new HashSet<>(t.columns().stream().map(ExportColumn::name).toList());
            covered.addAll(t.excludedColumns().keySet());
            assertThat(real).as("cột của " + t.name() + " chưa được xếp vào xuất/loại").isEqualTo(covered);
        }
        Set<String> unclassified = new HashSet<>(columnsByTable.keySet());
        unclassified.removeAll(exportedTables);
        unclassified.removeAll(excludedTables);
        assertThat(unclassified).as("bảng của sales_db chưa được xếp vào xuất/loại").isEmpty();
    }

    @Test
    void tenantRongVanCoDuFileChiCoDongTieuDeVaMetaRowsBang0() throws Exception {
        Map<String, byte[]> zip = export(EMPTY);
        for (ExportTable t : SalesExportCatalog.tables()) {
            assertThat(parse(text(zip.get(t.file())))).as(t.name()).hasSize(1);
        }
        assertThat(new String(zip.get("_meta.json"), StandardCharsets.UTF_8)).doesNotContain("\"rows\":1")
                .contains("\"rows\":0");
    }

    @Test
    void vaiTroUngDungChiuRlsKhiDocKhongLocTenant() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TenantContext.set(A);
        try {
            // Không lọc tenant_id: chỉ còn dòng của A nhờ RLS (nếu RLS hỏng, bài kiểm export phía trên mất ý nghĩa)
            assertThat(jdbc.queryForObject("select count(*) from sales_orders", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select count(*) from invoices", Long.class)).isEqualTo(1L);
        } finally {
            TenantContext.clear();
        }
        // Không có tenant: fail-closed
        assertThat(jdbc.queryForObject("select count(*) from sales_orders", Long.class)).isZero();
    }
}
