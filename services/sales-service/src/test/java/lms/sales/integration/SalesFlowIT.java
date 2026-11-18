package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lms.sales.ordering.application.OrderResponse;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.AdjustStockUseCase;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.ReconcileStockUseCase;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IT bằng vai trò sales_app (NOSUPERUSER NOBYPASSRLS) trên PostgreSQL thật: cách ly tenant mọi bảng, nhập kho, đơn
 * nhiều dòng, vượt tồn không tạo đơn một phần, gửi lại clientRequestId, đối soát sổ cái, và test đồng thời (cùng đầu
 * sách còn 1, hai đơn thứ tự ngược nhau). Cần Docker: mvn -o -pl services/sales-service -am test -Dtest=SalesFlowIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44. Catalog được giả bằng CatalogLookupPort.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class SalesFlowIT {

    private static final String B = "/internal/sales";
    private static final List<String> BANG = List.of("sales_settings", "sales_counters", "stock_items", "stock_ledger",
            "suppliers", "goods_receipts", "goods_receipt_lines", "sales_orders", "sales_order_lines",
            "payment_records", "sales_audit_log");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    static final Map<UUID, CatalogLookupPort.CatalogTitle> TITLES = new ConcurrentHashMap<>();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlacePosOrderUseCase placePos;
    @Autowired lms.sales.ordering.application.PaymentIntentUseCases intentUseCases;
    @Autowired RegisterStockItemUseCase registerItem;
    @Autowired AdjustStockUseCase adjust;
    @Autowired ReconcileStockUseCase reconcile;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean CatalogLookupPort catalog;

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID user = UUID.randomUUID();

    @BeforeEach
    void stubCatalog() {
        when(catalog.lookup(any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            Map<UUID, CatalogLookupPort.CatalogTitle> out = new java.util.HashMap<>();
            ids.forEach(id -> {
                if (TITLES.containsKey(id)) {
                    out.put(id, TITLES.get(id));
                }
            });
            return out;
        });
    }

    private RequestPostProcessor admin(UUID tenant) {
        return jwt().jwt(j -> {
            j.subject(user.toString()).claim("admin", true).claim("modules", List.of("SALES"));
            if (tenant != null) {
                j.claim("tenantId", tenant.toString());
            }
        });
    }

    private UUID title(String name, long coverPrice) {
        UUID id = UUID.randomUUID();
        TITLES.put(id, new CatalogLookupPort.CatalogTitle(id, name, "Tac gia", "ISBN-" + id.toString().substring(0, 8),
                BigDecimal.valueOf(coverPrice)));
        return id;
    }

    /** Đưa đầu sách vào bán và nhập tồn ban đầu bằng điều chỉnh (sổ cái khớp), trong tenant chỉ định. */
    private UUID stocked(UUID tenant, String name, long coverPrice, long stock) {
        UUID id = title(name, coverPrice);
        TenantContext.runAs(tenant, () -> {
            registerItem.execute(tenant, user, id, null, null);
            if (stock > 0) {
                adjust.execute(tenant, user, id, stock, "ton dau");
            }
        });
        return id;
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** ADR-0021: POS hai bước; xác nhận thu tay ngay để đơn thành COMPLETED + PAID và tồn được xuất như trước. */
    private lms.sales.ordering.application.OrderResponse paid(UUID tenant, UUID actor,
            lms.sales.ordering.application.PosOrderResult r) {
        if (r.paymentIntent() != null) {
            intentUseCases.confirm(tenant, actor, r.paymentIntent().id(),
                    new lms.sales.ordering.application.PaymentIntentUseCases.ConfirmCommand(null, "IT-REF"));
        }
        return r.order();
    }

    private OrderResponse sell(UUID tenant, Discount orderDiscount, UUID requestId, Object... titleQtyPairs) {
        List<PlacePosOrderCommand.Line> lines = new ArrayList<>();
        for (int i = 0; i < titleQtyPairs.length; i += 2) {
            lines.add(new PlacePosOrderCommand.Line((UUID) titleQtyPairs[i], (Integer) titleQtyPairs[i + 1], null));
        }
        return TenantContext.callAs(tenant, () -> paid(tenant, user, placePos.createWithDiscountAndPayment(
                new PlacePosOrderCommand(tenant, user, lines, PaymentMethod.CASH, null, null, null, orderDiscount,
                        requestId))));
    }

    private long scalar(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void assertReconciled(UUID tenant, UUID title) {
        var r = TenantContext.callAs(tenant, () -> reconcile.execute(tenant, title));
        assertThat(r.consistent()).as("on_hand/reserved phải bằng tổng sổ cái: " + r).isTrue();
        assertThat(r.onHand()).isGreaterThanOrEqualTo(0);
    }

    // ---------------------------------------------------------------- cách ly tenant

    @Test
    void cachLyTenantTrenMoiBangChinhQuaHttpVaDb() throws Exception {
        UUID title = title("Sach rieng cua A", 100_000);
        // Tenant A: cấu hình, đưa vào bán, nhà cung cấp, nhập kho, bán POS (tạo dòng ở mọi bảng)
        mvc.perform(put(B + "/settings").with(admin(tenantA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vatRateBps\":800,\"pricesIncludeVat\":false,\"returnWindowDays\":10,"
                                + "\"fixedShippingFee\":0,\"lowStockThreshold\":2,\"walkInPiiRetentionMonths\":6,"
                                + "\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post(B + "/stock-items").with(admin(tenantA)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"titleId\":\"" + title + "\"}")).andExpect(status().isCreated());
        String supplier = body(mvc.perform(post(B + "/suppliers").with(admin(tenantA))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"NCC A\"}")
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isCreated())).get("id").asText();
        String receipt = body(mvc.perform(post(B + "/goods-receipts").with(admin(tenantA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":\"" + supplier + "\",\"lines\":[{\"titleId\":\"" + title
                        + "\",\"quantity\":10,\"unitCost\":50000}],\"clientRequestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())).get("id").asText();
        mvc.perform(post(B + "/goods-receipts/" + receipt + "/confirmation").with(admin(tenantA)))
                .andExpect(status().isOk());
        JsonNode created = body(mvc.perform(post(B + "/pos-orders").with(admin(tenantA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":3}],\"paymentMethod\":\"CASH\","
                        + "\"walkIn\":{\"name\":\"Tran Thi Bich\",\"phone\":\"0987654321\"},\"clientRequestId\":\""
                        + UUID.randomUUID() + "\"}")).andExpect(status().isCreated()));
        String order = created.get("id").asText();
        // ADR-0021: POS hai bước; xác nhận thu để đơn có bản ghi thu và tồn được xuất
        TenantContext.runAs(tenantA, () -> intentUseCases.confirm(tenantA, user,
                UUID.fromString(created.get("paymentIntent").get("id").asText()),
                new lms.sales.ordering.application.PaymentIntentUseCases.ConfirmCommand(null, "IT-REF")));

        // Mọi bảng chính có dữ liệu của A và không có của B (kiểm bằng superuser, bỏ qua RLS)
        for (String table : BANG) {
            assertThat(scalar("select count(*) from " + table + " where tenant_id = '" + tenantA + "'"))
                    .as(table + " của A").isPositive();
            assertThat(scalar("select count(*) from " + table + " where tenant_id = '" + tenantB + "'"))
                    .as(table + " của B").isZero();
        }

        // Tenant B không thấy/ghi được dữ liệu của A qua HTTP
        mvc.perform(get(B + "/stock-items").with(admin(tenantB))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(B + "/stock-items/" + title).with(admin(tenantB))).andExpect(status().isNotFound());
        mvc.perform(get(B + "/stock-items/" + title + "/ledger").with(admin(tenantB))).andExpect(status().isNotFound());
        mvc.perform(get(B + "/orders").with(admin(tenantB))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(B + "/orders/" + order).with(admin(tenantB))).andExpect(status().isNotFound());
        mvc.perform(get(B + "/orders/" + order + "/contact").with(admin(tenantB))).andExpect(status().isNotFound());
        mvc.perform(get(B + "/orders/" + order + "/receipt").with(admin(tenantB))).andExpect(status().isNotFound());
        mvc.perform(get(B + "/goods-receipts/" + receipt).with(admin(tenantB))).andExpect(status().isNotFound());
        mvc.perform(post(B + "/goods-receipts/" + receipt + "/confirmation").with(admin(tenantB)))
                .andExpect(status().isNotFound());
        mvc.perform(get(B + "/suppliers").with(admin(tenantB))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(put(B + "/suppliers/" + supplier).with(admin(tenantB)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"chiem quyen\"}")).andExpect(status().isNotFound());
        // Bulk update tồn kho (JPQL có tenant tường minh) của B không chạm hàng của A
        mvc.perform(post(B + "/stock-adjustments").with(admin(tenantB)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + title + "\",\"delta\":-5,\"reason\":\"tan cong\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_ITEM_NOT_REGISTERED"));
        mvc.perform(post(B + "/stocktakes").with(admin(tenantB)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + title + "\",\"countedOnHand\":0,\"reason\":\"tan cong\"}"))
                .andExpect(status().isNotFound());
        assertThat(scalar("select on_hand from stock_items where tenant_id = '" + tenantA + "' and title_id = '"
                + title + "'")).isEqualTo(7);
        // Cấu hình của B là mặc định (chưa lưu), của A giữ nguyên
        mvc.perform(get(B + "/settings").with(admin(tenantB))).andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.vatRateBps").value(500));
        mvc.perform(get(B + "/settings").with(admin(tenantA))).andExpect(jsonPath("$.vatRateBps").value(800));
        // Cùng đầu sách đưa vào bán được ở tenant B (UNIQUE theo tenant, không đụng A)
        TenantContext.runAs(tenantB, () -> registerItem.execute(tenantB, user, title, null, null));

        // Vai trò app không đặt tenant: fail-closed trên mọi bảng; đặt B: không thấy gì của A
        try (Connection c = AppRoleDatabase.appRole(postgres); Statement s = c.createStatement()) {
            for (String table : BANG) {
                try (ResultSet rs = s.executeQuery("select count(*) from " + table)) {
                    rs.next();
                    assertThat(rs.getLong(1)).as(table + " không tenant").isZero();
                }
            }
        }
    }

    @Test
    void khongCoTenantThiTenantRequired() throws Exception {
        UUID id = UUID.randomUUID();
        for (var req : List.of(get(B + "/stock-items"), get(B + "/orders"), get(B + "/settings"),
                get(B + "/goods-receipts"), get(B + "/suppliers"), get(B + "/orders/" + id))) {
            mvc.perform(req.with(admin(null))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        }
        mvc.perform(post(B + "/pos-orders").with(admin(null)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"titleId\":\"" + id + "\",\"quantity\":1}],"
                                + "\"paymentMethod\":\"CASH\",\"clientRequestId\":\"x\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        // Bên dưới HTTP: ghi khi không có tenant bị chặn ở TenantContext
        UUID known = title("Khong tenant", 1_000);
        assertThatThrownBy(() -> registerItem.execute(tenantA, user, known, null, null))
                .isInstanceOf(RuntimeException.class);
        assertThat(scalar("select count(*) from stock_items where title_id = '" + known + "'")).isZero();
    }

    // ---------------------------------------------------------------- nhập kho, đơn, đối soát

    @Test
    void nhapKhoXacNhanTangTonVaGhiSoCaiVaGuiLaiKhongNhanDoi() throws Exception {
        UUID title = title("Sach nhap kho", 80_000);
        mvc.perform(post(B + "/stock-items").with(admin(tenantA)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"titleId\":\"" + title + "\"}")).andExpect(status().isCreated());
        String supplier = body(mvc.perform(post(B + "/suppliers").with(admin(tenantA))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"NCC nhap\"}"))
                .andExpect(status().isCreated())).get("id").asText();
        String request = UUID.randomUUID().toString();
        String create = "{\"supplierId\":\"" + supplier + "\",\"lines\":[{\"titleId\":\"" + title
                + "\",\"quantity\":12,\"unitCost\":40000}],\"clientRequestId\":\"" + request + "\"}";
        String id = body(mvc.perform(post(B + "/goods-receipts").with(admin(tenantA))
                .contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.totalCost").value(480_000)))
                .get("id").asText();
        // gửi lại cùng clientRequestId trả phiếu cũ
        mvc.perform(post(B + "/goods-receipts").with(admin(tenantA)).contentType(MediaType.APPLICATION_JSON)
                .content(create)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        assertThat(scalar("select count(*) from goods_receipts where client_request_id = '" + request + "'"))
                .isEqualTo(1);
        // nháp chưa đổi tồn
        mvc.perform(get(B + "/stock-items/" + title).with(admin(tenantA))).andExpect(jsonPath("$.onHand").value(0));

        mvc.perform(post(B + "/goods-receipts/" + id + "/confirmation").with(admin(tenantA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(get(B + "/stock-items/" + title).with(admin(tenantA)))
                .andExpect(jsonPath("$.onHand").value(12)).andExpect(jsonPath("$.titleName").value("Sach nhap kho"));
        mvc.perform(get(B + "/stock-items/" + title + "/ledger").with(admin(tenantA)))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].type").value("RECEIPT"))
                .andExpect(jsonPath("$.items[0].deltaOnHand").value(12))
                .andExpect(jsonPath("$.items[0].refType").value("GOODS_RECEIPT"));
        // xác nhận lại: 409, tồn không đổi
        mvc.perform(post(B + "/goods-receipts/" + id + "/confirmation").with(admin(tenantA)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECEIPT_STATE_CONFLICT"));
        assertThat(scalar("select on_hand from stock_items where title_id = '" + title + "' and tenant_id = '"
                + tenantA + "'")).isEqualTo(12);
        assertReconciled(tenantA, title);
        // audit cùng transaction
        assertThat(scalar("select count(*) from sales_audit_log where tenant_id = '" + tenantA
                + "' and action = 'GOODS_RECEIPT_CONFIRMED' and aggregate_id = '" + id + "'")).isEqualTo(1);
    }

    @Test
    void phieuNhapCoDongChuaDuaVaoBanBiTuChoiVaKhongTaoPhieu() throws Exception {
        UUID unregistered = title("Chua dang ky", 10_000);
        String supplier = body(mvc.perform(post(B + "/suppliers").with(admin(tenantA))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"NCC loi\"}"))
                .andExpect(status().isCreated())).get("id").asText();
        mvc.perform(post(B + "/goods-receipts").with(admin(tenantA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplier + "\",\"lines\":[{\"titleId\":\"" + unregistered
                                + "\",\"quantity\":1,\"unitCost\":1}],\"clientRequestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_ITEM_NOT_REGISTERED"));
    }

    @Test
    void donNhieuDongTruDungTungDongVaTongKhopPhieu() throws Exception {
        UUID x = stocked(tenantA, "Nhieu dong X", 100_000, 10);
        UUID y = stocked(tenantA, "Nhieu dong Y", 50_000, 10);
        OrderResponse o = sell(tenantA, Discount.amount(15_000), UUID.randomUUID(), x, 2, y, 3);
        // 2*100000 + 3*50000 = 350000, giảm đơn 15000, giá đã gồm thuế (mặc định) nên tổng = 335000
        assertThat(o.subtotal()).isEqualTo(350_000);
        assertThat(o.discountTotal()).isEqualTo(15_000);
        assertThat(o.grandTotal()).isEqualTo(335_000);
        assertThat(o.lines()).hasSize(2);
        assertThat(o.lines().stream().mapToLong(OrderResponse.Line::lineTotal).sum() + o.shippingFee())
                .isEqualTo(o.grandTotal());
        assertThat(scalar("select on_hand from stock_items where title_id = '" + x + "'")).isEqualTo(8);
        assertThat(scalar("select on_hand from stock_items where title_id = '" + y + "'")).isEqualTo(7);
        assertThat(scalar("select count(*) from stock_ledger where type = 'SALE' and ref_id = '" + o.id() + "'"))
                .isEqualTo(2);
        assertThat(scalar("select count(*) from payment_records where order_id = '" + o.id()
                + "' and amount = 335000")).isEqualTo(1);
        assertReconciled(tenantA, x);
        assertReconciled(tenantA, y);
        // Phiếu in và số đơn tăng dần theo tenant
        mvc.perform(get(B + "/orders/" + o.id() + "/receipt").with(admin(tenantA)))
                .andExpect(jsonPath("$.lines.length()").value(2)).andExpect(jsonPath("$.grandTotal").value(335_000));
        OrderResponse o2 = sell(tenantA, null, UUID.randomUUID(), x, 1);
        assertThat(o2.orderNo()).startsWith("DH-").isNotEqualTo(o.orderNo());
    }

    @Test
    void vuotTonTuChoiVaKhongTaoDonMotPhan() throws Exception {
        UUID ok = stocked(tenantA, "Du hang", 20_000, 5);
        UUID thieu = stocked(tenantA, "Thieu hang", 30_000, 1);
        long ordersBefore = scalar("select count(*) from sales_orders where tenant_id = '" + tenantA + "'");
        long ledgerBefore = scalar("select count(*) from stock_ledger where tenant_id = '" + tenantA + "'");
        UUID request = UUID.randomUUID();
        assertThatThrownBy(() -> sell(tenantA, null, request, ok, 2, thieu, 2))
                .isInstanceOfSatisfying(SalesException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
                    assertThat(e.details()).containsEntry("titleId", thieu.toString()).containsEntry("available", 1L);
                });
        assertThat(scalar("select count(*) from sales_orders where tenant_id = '" + tenantA + "'"))
                .isEqualTo(ordersBefore);
        assertThat(scalar("select count(*) from stock_ledger where tenant_id = '" + tenantA + "'"))
                .isEqualTo(ledgerBefore);
        assertThat(scalar("select count(*) from sales_orders where client_request_id = '" + request + "'")).isZero();
        assertThat(scalar("select on_hand from stock_items where title_id = '" + ok + "'")).isEqualTo(5);
        assertThat(scalar("select on_hand from stock_items where title_id = '" + thieu + "'")).isEqualTo(1);
        assertReconciled(tenantA, ok);
        assertReconciled(tenantA, thieu);
    }

    @Test
    void guiLaiCungClientRequestIdKhongTruHaiLan() throws Exception {
        UUID t = stocked(tenantA, "Gui lai", 10_000, 5);
        UUID request = UUID.randomUUID();
        OrderResponse first = sell(tenantA, null, request, t, 2);
        OrderResponse again = sell(tenantA, null, request, t, 2);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(scalar("select on_hand from stock_items where title_id = '" + t + "'")).isEqualTo(3);
        assertThat(scalar("select count(*) from sales_orders where client_request_id = '" + request + "'"))
                .isEqualTo(1);
        assertReconciled(tenantA, t);
    }

    @Test
    void auditKhongChuaDuLieuCaNhan() throws Exception {
        UUID t = stocked(tenantA, "Audit sach", 10_000, 5);
        mvc.perform(post(B + "/pos-orders").with(admin(tenantA)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"titleId\":\"" + t + "\",\"quantity\":1}],\"paymentMethod\":\"CASH\","
                        + "\"walkIn\":{\"name\":\"Le Van Quy Hiem\",\"phone\":\"0911222333\"},\"clientRequestId\":\""
                        + UUID.randomUUID() + "\"}")).andExpect(status().isCreated());
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select string_agg(data::text, ' ') from sales_audit_log where tenant_id = '"
                     + tenantA + "'")) {
            rs.next();
            assertThat(rs.getString(1)).doesNotContain("Quy Hiem").doesNotContain("0911222333");
        }
        assertThat(scalar("select count(*) from sales_audit_log where action = 'ORDER_CREATED' and tenant_id = '"
                + tenantA + "' and data->>'channel' = 'POS'")).isPositive();
    }

    // ---------------------------------------------------------------- đồng thời trên PostgreSQL thật

    @Test
    void nLuongBanCungDauSachConMotDungMotThanhCongTonKhongAm() throws Exception {
        UUID t = stocked(tenantA, "Cuoi cung", 10_000, 1);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID request = UUID.randomUUID();
            futures.add(pool.submit((Callable<Object>) () -> {
                start.await();
                try {
                    return sell(tenantA, null, request, t, 1);
                } catch (SalesException e) {
                    return e;
                }
            }));
        }
        start.countDown();
        int ok = 0;
        int outOfStock = 0;
        for (Future<Object> f : futures) {
            Object r = f.get(60, TimeUnit.SECONDS);
            if (r instanceof OrderResponse) {
                ok++;
            } else if (r instanceof SalesException e && e.code() == ErrorCode.INSUFFICIENT_STOCK) {
                outOfStock++;
            } else {
                throw new AssertionError("Kết quả không mong đợi: " + r);
            }
        }
        pool.shutdown();
        assertThat(ok).as("đúng một luồng bán được").isEqualTo(1);
        assertThat(outOfStock).isEqualTo(threads - 1);
        assertThat(scalar("select on_hand from stock_items where title_id = '" + t + "'")).isZero();
        assertThat(scalar("select count(*) from sales_order_lines where title_id = '" + t + "'")).isEqualTo(1);
        assertThat(scalar("select count(*) from stock_ledger where type = 'SALE' and stock_item_id = "
                + "(select id from stock_items where title_id = '" + t + "')")).isEqualTo(1);
        assertReconciled(tenantA, t);
    }

    @Test
    void haiDonNhieuDongThuTuNguocNhauKhongDeadlock() throws Exception {
        UUID x = stocked(tenantA, "Deadlock X", 10_000, 200);
        UUID y = stocked(tenantA, "Deadlock Y", 10_000, 200);
        int perDirection = 25;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderResponse>> futures = new ArrayList<>();
        for (int i = 0; i < perDirection; i++) {
            UUID r1 = UUID.randomUUID();
            UUID r2 = UUID.randomUUID();
            futures.add(pool.submit(() -> {
                start.await();
                return sell(tenantA, null, r1, x, 1, y, 2);
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return sell(tenantA, null, r2, y, 2, x, 1);
            }));
        }
        start.countDown();
        for (Future<OrderResponse> f : futures) {
            assertThat(f.get(90, TimeUnit.SECONDS).status().name()).isEqualTo("COMPLETED");
        }
        pool.shutdown();
        int orders = perDirection * 2;
        assertThat(scalar("select on_hand from stock_items where title_id = '" + x + "'")).isEqualTo(200 - orders);
        assertThat(scalar("select on_hand from stock_items where title_id = '" + y + "'"))
                .isEqualTo(200 - 2L * orders);
        assertReconciled(tenantA, x);
        assertReconciled(tenantA, y);
    }

    @Test
    void haiYeuCauCungClientRequestIdChayDongThoiChiTruMotLan() throws Exception {
        UUID t = stocked(tenantA, "Song song cung id", 10_000, 10);
        UUID request = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return sell(tenantA, null, request, t, 1);
            }));
        }
        start.countDown();
        List<UUID> ids = new ArrayList<>();
        for (Future<OrderResponse> f : futures) {
            ids.add(f.get(60, TimeUnit.SECONDS).id());
        }
        pool.shutdown();
        assertThat(ids.stream().distinct().collect(Collectors.toList())).hasSize(1);
        assertThat(scalar("select on_hand from stock_items where title_id = '" + t + "'")).isEqualTo(9);
        assertReconciled(tenantA, t);
    }

    @Test
    void capNhatCauHinhXungDotVersionTra409() throws Exception {
        UUID tenant = UUID.randomUUID();
        String ok = "{\"vatRateBps\":1000,\"pricesIncludeVat\":true,\"returnWindowDays\":7,\"fixedShippingFee\":0,"
                + "\"lowStockThreshold\":5,\"walkInPiiRetentionMonths\":12,\"version\":";
        mvc.perform(put(B + "/settings").with(admin(tenant)).contentType(MediaType.APPLICATION_JSON)
                .content(ok + "0}")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(B + "/settings").with(admin(tenant)).contentType(MediaType.APPLICATION_JSON)
                .content(ok + "0}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTINGS_VERSION_CONFLICT"));
        mvc.perform(put(B + "/settings").with(admin(tenant)).contentType(MediaType.APPLICATION_JSON)
                .content(ok + "1}")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get(B + "/settings").with(admin(tenant))).andExpect(jsonPath("$.vatRateBps").value(1000));
    }
}
