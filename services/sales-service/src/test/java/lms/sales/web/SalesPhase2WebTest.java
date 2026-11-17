package lms.sales.web;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.USER;
import static lms.sales.support.WebTokens.storefrontService;
import static lms.sales.support.WebTokens.userWithKeys;
import static lms.sales.support.WebTokens.withKey;
import static lms.sales.support.WebTokens.withKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.ordering.infrastructure.web.PosOrderController;
import lms.sales.ordering.infrastructure.web.RefundController;
import lms.sales.ordering.infrastructure.web.SalesAuditController;
import lms.sales.ordering.infrastructure.web.SalesOrderController;
import lms.sales.ordering.infrastructure.web.SalesReturnController;
import lms.sales.ordering.infrastructure.web.ShopController;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.infrastructure.web.StorefrontController;
import lms.sales.support.Harness;
import lms.sales.support.HarnessBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Hành vi HTTP pha 2: khách chỉ thấy đơn của mình, trả hàng thường/quá hạn, token dịch vụ storefront, audit. */
@WebMvcTest({PosOrderController.class, SalesOrderController.class, ShopController.class, StorefrontController.class,
        SalesReturnController.class, RefundController.class, SalesAuditController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class SalesPhase2WebTest {

    private static final String B = "/internal/sales";
    private static final String[] SHOP_KEYS = {"ShopController/placeOrder", "ShopController/myOrders",
            "ShopController/myOrder", "ShopController/cancelMyOrder"};

    // Harness dùng chung trong một context: mỗi test dùng khách riêng để danh sách đơn không lẫn nhau
    private final UUID CUSTOMER_A = UUID.randomUUID();
    private final UUID CUSTOMER_B = UUID.randomUUID();

    @Autowired
    MockMvc mvc;

    @Autowired
    Harness h;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    private UUID book(String name, long coverPrice, long stock) {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, name, "Tac gia", "ISBN-SECRET-" + name,
                BigDecimal.valueOf(coverPrice), "Mo ta", true));
        h.registerItem.execute(TENANT, USER, id, null, null);
        if (stock > 0) {
            h.adjust.execute(TENANT, USER, id, stock, "ton dau");
        }
        return id;
    }

    private String shopBody(UUID title, int qty, UUID requestId) {
        return "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":" + qty + "}],\"fulfilment\":\"PICKUP\","
                + "\"paymentMethod\":\"BANK_TRANSFER\",\"recipient\":{\"name\":\"Nguyen Van An\","
                + "\"phone\":\"0912345678\",\"address\":\"12 Le Loi, Quan 1, TP HCM\"},\"clientRequestId\":\""
                + requestId + "\"}";
    }

    private JsonNode placeShopOrder(UUID customer, UUID title, int qty) throws Exception {
        MvcResult r = mvc.perform(post(B + "/shop/orders").with(userWithKeys(customer, SHOP_KEYS))
                        .contentType(MediaType.APPLICATION_JSON).content(shopBody(title, qty, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith(B + "/shop/orders/")))
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void khachDatDonThayDayDuThongTinCuaMinhVaGiuTon() throws Exception {
        UUID title = book("Sach Shop", 100_000, 5);
        JsonNode o = placeShopOrder(CUSTOMER_A, title, 2);
        assertThat(o.get("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(o.get("paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(o.has("customerUserId")).isFalse();
        assertThat(o.get("recipient").get("name").asText()).isEqualTo("Nguyen Van An"); // đầy đủ, của chính khách
        assertThat(o.get("recipient").get("address").asText()).isEqualTo("12 Le Loi, Quan 1, TP HCM");
        assertThat(h.item(title).reserved()).isEqualTo(2);
        assertThat(h.item(title).onHand()).isEqualTo(5);
    }

    @Test
    void khachKhacKhongThayVaKhongHuyDuocDonCuaNguoiKhac() throws Exception {
        UUID title = book("Sach Rieng", 100_000, 5);
        String id = placeShopOrder(CUSTOMER_A, title, 1).get("id").asText();

        mvc.perform(get(B + "/shop/orders/" + id).with(userWithKeys(CUSTOMER_B, SHOP_KEYS)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(post(B + "/shop/orders/" + id + "/cancellation").with(userWithKeys(CUSTOMER_B, SHOP_KEYS)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(get(B + "/shop/orders").with(userWithKeys(CUSTOMER_B, SHOP_KEYS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(B + "/shop/orders").with(userWithKeys(CUSTOMER_A, SHOP_KEYS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        // chủ đơn hủy được và tồn được hoàn
        mvc.perform(post(B + "/shop/orders/" + id + "/cancellation").with(userWithKeys(CUSTOMER_A, SHOP_KEYS))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"doi y\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(h.item(title).reserved()).isZero();
    }

    private static final java.util.Set<String> SHOP_ORDER_FIELDS = java.util.Set.of("id", "orderNo", "status",
            "paymentStatus", "paymentMethod", "fulfilment", "recipient", "currency", "subtotal", "discountTotal",
            "vatTotal", "shippingFee", "grandTotal", "createdAt", "confirmedAt", "paidAt", "completedAt",
            "cancelledAt", "lines");
    private static final java.util.Set<String> SHOP_LINE_FIELDS = java.util.Set.of("titleId", "titleName",
            "quantity", "unitPrice", "lineTotal");

    private static java.util.Set<String> names(JsonNode n) {
        java.util.Set<String> r = new java.util.HashSet<>();
        n.fieldNames().forEachRemaining(r::add);
        return r;
    }

    @Test
    void phanHoiChoKhachChiCoTruongTrongAllowlistKhongLoISBNHayDinhDanh() throws Exception {
        UUID title = book("Sach Allow", 100_000, 5);
        JsonNode created = placeShopOrder(CUSTOMER_A, title, 1);
        String id = created.get("id").asText();
        MvcResult list = mvc.perform(get(B + "/shop/orders").with(userWithKeys(CUSTOMER_A, SHOP_KEYS)))
                .andExpect(status().isOk()).andReturn();
        MvcResult one = mvc.perform(get(B + "/shop/orders/" + id).with(userWithKeys(CUSTOMER_A, SHOP_KEYS)))
                .andExpect(status().isOk()).andReturn();
        MvcResult cancelled = mvc.perform(post(B + "/shop/orders/" + id + "/cancellation")
                        .with(userWithKeys(CUSTOMER_A, SHOP_KEYS))).andExpect(status().isOk()).andReturn();
        for (JsonNode o : List.of(created, json.readTree(one.getResponse().getContentAsString()),
                json.readTree(cancelled.getResponse().getContentAsString()),
                json.readTree(list.getResponse().getContentAsString()).get("items").get(0))) {
            assertThat(names(o)).isEqualTo(SHOP_ORDER_FIELDS);
            assertThat(names(o.get("lines").get(0))).isEqualTo(SHOP_LINE_FIELDS);
        }
        for (String body : List.of(created.toString(), list.getResponse().getContentAsString(),
                one.getResponse().getContentAsString(), cancelled.getResponse().getContentAsString())) {
            assertThat(body).doesNotContain("ISBN-SECRET").doesNotContain(CUSTOMER_A.toString())
                    .doesNotContain(TENANT.toString()).doesNotContain("cancelReason").doesNotContain("version");
        }
    }

    @Test
    void clientRequestIdDonOnlineBatBuocUuidVaTrungCuaNguoiKhacLa409KhongLoDuLieu() throws Exception {
        UUID title = book("Sach Khoa", 100_000, 9);
        mvc.perform(post(B + "/shop/orders").with(userWithKeys(CUSTOMER_A, SHOP_KEYS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shopBody(title, 1, UUID.randomUUID()).replaceAll("\"clientRequestId\":\"[^\"]+\"",
                                "\"clientRequestId\":\"cart-1\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        UUID key = UUID.randomUUID();
        MvcResult first = mvc.perform(post(B + "/shop/orders").with(userWithKeys(CUSTOMER_A, SHOP_KEYS))
                        .contentType(MediaType.APPLICATION_JSON).content(shopBody(title, 1, key)))
                .andExpect(status().isCreated()).andReturn();
        // chính mình gửi lại: trả đơn cũ
        MvcResult again = mvc.perform(post(B + "/shop/orders").with(userWithKeys(CUSTOMER_A, SHOP_KEYS))
                        .contentType(MediaType.APPLICATION_JSON).content(shopBody(title, 1, key)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(again.getResponse().getContentAsString()).get("id"))
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()).get("id"));
        // người khác dùng khóa đó: 409, thân không có dữ liệu đơn
        MvcResult other = mvc.perform(post(B + "/shop/orders").with(userWithKeys(CUSTOMER_B, SHOP_KEYS))
                        .contentType(MediaType.APPLICATION_JSON).content(shopBody(title, 1, key)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIENT_REQUEST_CONFLICT"))
                .andReturn();
        assertThat(other.getResponse().getContentAsString()).doesNotContain("Nguyen Van An").doesNotContain("0912")
                .doesNotContain("orderNo").doesNotContain(json.readTree(first.getResponse().getContentAsString())
                        .get("id").asText());
    }

    @Test
    void yeuCauDatHangBoQuaTruongTuDoClientGuiThemGiaVaDinhDanh() throws Exception {
        UUID title = book("Sach Gia", 100_000, 9);
        String body = shopBody(title, 2, UUID.randomUUID()).replaceFirst("^\\{", "{\"customerUserId\":\""
                + UUID.randomUUID() + "\",\"tenantId\":\"" + UUID.randomUUID() + "\",\"status\":\"COMPLETED\","
                + "\"paymentStatus\":\"PAID\",\"shippingFee\":1,\"discountTotal\":99999,\"grandTotal\":1,"
                + "\"unitPrice\":1,");
        MvcResult r = mvc.perform(post(B + "/shop/orders").with(userWithKeys(CUSTOMER_A, SHOP_KEYS))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        JsonNode o = json.readTree(r.getResponse().getContentAsString());
        assertThat(o.get("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(o.get("paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(o.get("grandTotal").asLong()).isEqualTo(200_000);
        assertThat(o.get("discountTotal").asLong()).isZero();
        assertThat(o.get("lines").get(0).get("unitPrice").asLong()).isEqualTo(100_000);
        // đơn gắn với người trong token (A xem được, B không)
        mvc.perform(get(B + "/shop/orders/" + o.get("id").asText()).with(userWithKeys(CUSTOMER_B, SHOP_KEYS)))
                .andExpect(status().isNotFound());
    }

    @Test
    void trangCuaKhachToiDa50() throws Exception {
        mvc.perform(get(B + "/shop/orders?size=500").with(userWithKeys(CUSTOMER_A, SHOP_KEYS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void khachThieuKhoaBi403VaDatQuaTonTra409() throws Exception {
        UUID title = book("Sach Het", 100_000, 1);
        mvc.perform(post(B + "/shop/orders").with(userWithKeys(CUSTOMER_A, "ShopController/myOrders"))
                        .contentType(MediaType.APPLICATION_JSON).content(shopBody(title, 1, UUID.randomUUID())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post(B + "/shop/orders").with(userWithKeys(CUSTOMER_A, SHOP_KEYS))
                        .contentType(MediaType.APPLICATION_JSON).content(shopBody(title, 2, UUID.randomUUID())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        // COD với nhận tại cửa hàng không hợp lệ
        mvc.perform(post(B + "/shop/orders").with(userWithKeys(CUSTOMER_A, SHOP_KEYS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shopBody(title, 1, UUID.randomUUID()).replace("BANK_TRANSFER", "COD")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void nhanVienXacNhanThuTienHoanTatVaAuditCoNguoiThoiDiemSoTien() throws Exception {
        UUID title = book("Sach Flow", 100_000, 5);
        String id = placeShopOrder(CUSTOMER_A, title, 1).get("id").asText();
        mvc.perform(post(B + "/orders/" + id + "/confirmation").with(withKey("SalesOrderController/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"shippingFee\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(post(B + "/orders/" + id + "/ready-for-pickup")
                        .with(withKey("SalesOrderController/markReadyForPickup")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"));
        mvc.perform(post(B + "/orders/" + id + "/completion").with(withKey("SalesOrderController/complete")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PAID"));
        mvc.perform(post(B + "/orders/" + id + "/payment").with(withKey("SalesOrderController/markPaid"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"BANK_TRANSFER\",\"reference\":\"FT9\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("PAID"));
        mvc.perform(post(B + "/orders/" + id + "/payment").with(withKey("SalesOrderController/markPaid"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        mvc.perform(post(B + "/orders/" + id + "/completion").with(withKey("SalesOrderController/complete")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        // audit: người, số tiền, không PII
        MvcResult r = mvc.perform(get(B + "/audit-log?action=ORDER_PAID").with(withKey("SalesAuditController/search")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).contains("\"amount\":100000").contains(USER.toString()).doesNotContain("Nguyen Van An")
                .doesNotContain("0912345678");
        // phiếu in có dòng phí ship tách riêng
        mvc.perform(get(B + "/orders/" + id + "/receipt").with(withKey("SalesOrderController/receipt")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shippingFee").value(0));
    }

    @Test
    void huyDonVoiLyDoRongBi400VaDonDaThuSinhKhoanHoan() throws Exception {
        UUID title = book("Sach Hoan", 100_000, 5);
        String id = placeShopOrder(CUSTOMER_A, title, 1).get("id").asText();
        mvc.perform(post(B + "/orders/" + id + "/cancellation").with(withKey("SalesOrderController/cancel"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(B + "/orders/" + id + "/confirmation").with(withKey("SalesOrderController/confirm")))
                .andExpect(status().isOk());
        mvc.perform(post(B + "/orders/" + id + "/payment").with(withKey("SalesOrderController/markPaid"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(B + "/orders/" + id + "/cancellation").with(withKey("SalesOrderController/cancel"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"khach doi y\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        MvcResult r = mvc.perform(get(B + "/refunds?status=PENDING&size=100")
                        .with(withKey("RefundController/search"))).andExpect(status().isOk()).andReturn();
        String refundId = null;
        for (JsonNode item : json.readTree(r.getResponse().getContentAsString()).get("items")) {
            if (item.get("orderId").asText().equals(id)) {
                refundId = item.get("id").asText();
                assertThat(item.get("amount").asLong()).isEqualTo(100_000);
            }
        }
        assertThat(refundId).isNotNull();
        mvc.perform(post(B + "/refunds/" + refundId + "/completion").with(withKey("RefundController/markRefunded"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        mvc.perform(get(B + "/orders/" + id).with(withKey("SalesOrderController/get")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));
    }

    // ---- trả hàng ----

    private JsonNode posOrder(UUID title, int qty) throws Exception {
        String body = "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":" + qty + "}],"
                + "\"paymentMethod\":\"CASH\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        return json.readTree(mvc.perform(post(B + "/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private static String returnBody(JsonNode order, int qty, String extra) {
        return "{\"orderId\":\"" + order.get("id").asText() + "\",\"lines\":[{\"orderLineId\":\""
                + order.get("lines").get(0).get("id").asText() + "\",\"quantity\":" + qty
                + ",\"disposition\":\"RESTOCK\"}],\"reason\":\"loi in\"" + (extra == null ? "" : "," + extra)
                + ",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
    }

    @Test
    void traHangTrongHanQuaHanVaNgoaiLeCoKhoaRieng() throws Exception {
        UUID title = book("Sach Tra", 100_000, 10);
        JsonNode order = posOrder(title, 3);

        mvc.perform(post(B + "/returns").with(withKey("SalesReturnController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(returnBody(order, 1, null)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith(B + "/returns/")))
                .andExpect(jsonPath("$.refundTotal").value(100_000)).andExpect(jsonPath("$.late").value(false));

        h.clock.advanceSeconds(10L * 86_400);
        mvc.perform(post(B + "/returns").with(withKey("SalesReturnController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(returnBody(order, 1, null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETURN_WINDOW_EXPIRED"));
        // khóa của create không đủ để vào createLate
        mvc.perform(post(B + "/returns/late").with(withKey("SalesReturnController/create"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnBody(order, 1, "\"lateReason\":\"ok\"")))
                .andExpect(status().isForbidden());
        // thiếu lý do: 400 LATE_REASON_REQUIRED (không phải validate chung)
        mvc.perform(post(B + "/returns/late").with(withKey("SalesReturnController/createLate"))
                        .contentType(MediaType.APPLICATION_JSON).content(returnBody(order, 1, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LATE_REASON_REQUIRED"));
        mvc.perform(post(B + "/returns/late").with(withKey("SalesReturnController/createLate"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnBody(order, 1, "\"lateReason\":\"quan ly dong y\"")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.lateReason").value("quan ly dong y"));
        assertThat(h.audits().stream().filter(a -> a.action().equals("RETURN_LATE_APPROVED")).count()).isEqualTo(1);

        // vượt số đã mua (3, đã trả 2)
        mvc.perform(post(B + "/returns/late").with(withKey("SalesReturnController/createLate"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnBody(order, 2, "\"lateReason\":\"x\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETURN_QUANTITY_EXCEEDED"));
        mvc.perform(get(B + "/orders/" + order.get("id").asText() + "/returns")
                        .with(withKey("SalesReturnController/listByOrder")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    // ---- storefront / token dịch vụ ----

    @Test
    void tokenDichVuDocDuocDanhMucKhongLoTonVaKhongVaoEndpointKhac() throws Exception {
        UUID title = book("Sach Cong Khai", 100_000, 4);
        h.catalogPreviews.put(title, new CatalogLookupPort.CatalogPreview(title, "Sach Cong Khai", "Doan doc thu"));
        var svc = storefrontService(TENANT, List.of("SALES"));

        MvcResult r = mvc.perform(get(B + "/storefront/items?size=500").with(svc))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50)).andReturn();
        String listBody = r.getResponse().getContentAsString();
        assertThat(listBody).doesNotContain("onHand").doesNotContain("reserved").doesNotContain("available")
                .doesNotContain("ISBN-SECRET").doesNotContain("isbn").doesNotContain("tenant")
                .doesNotContain("previewText").doesNotContain("Mo ta");
        String detail = mvc.perform(get(B + "/storefront/items/" + title).with(svc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Mo ta"))
                .andExpect(jsonPath("$.inStock").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("onHand").doesNotContain("ISBN-SECRET").doesNotContain("previewText");
        mvc.perform(get(B + "/storefront/items/" + title + "/preview").with(svc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.previewText").value("Doan doc thu"));

        // token dịch vụ không dùng được ở endpoint nào khác
        mvc.perform(get(B + "/orders").with(svc)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(B + "/shop/orders").with(svc)).andExpect(status().isForbidden());
        // thiếu module SALES / tenant
        mvc.perform(get(B + "/storefront/items").with(storefrontService(TENANT, List.of("LENDING"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        mvc.perform(get(B + "/storefront/items").with(storefrontService(null, List.of("SALES"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        // người dùng thường vẫn cần khóa
        mvc.perform(get(B + "/storefront/items").with(withKeys(TENANT, List.of("SALES"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(B + "/storefront/items").with(withKey("StorefrontController/list")))
                .andExpect(status().isOk());
    }

    @Test
    void mucKhongTonTaiHoacTatBan404() throws Exception {
        var svc = storefrontService(TENANT, List.of("SALES"));
        mvc.perform(get(B + "/storefront/items/" + UUID.randomUUID()).with(svc)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_ITEM_NOT_FOUND"));
        mvc.perform(get(B + "/storefront/items/not-a-uuid").with(svc)).andExpect(status().isBadRequest());
    }
}
