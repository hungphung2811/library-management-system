package lms.sales.web;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.admin;
import static lms.sales.support.WebTokens.userWithKeys;
import static lms.sales.support.WebTokens.withKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.ordering.infrastructure.web.ShopController;
import lms.sales.settings.infrastructure.web.OrderAbusePolicyController;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.support.Harness;
import lms.sales.support.HarnessBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP của chống lạm dụng đặt hàng: giới hạn tốc độ (429), mã lỗi hạn mức/idempotency và API chính sách của tenant. */
@WebMvcTest({ShopController.class, OrderAbusePolicyController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class OrderAbuseWebTest {

    private static final String SHOP = "/internal/sales/shop/orders";
    private static final String POLICY = "/internal/sales/settings/order-abuse-policy";
    private static final String[] SHOP_KEYS = {"ShopController/placeOrder"};

    @Autowired
    MockMvc mvc;

    @Autowired
    Harness h;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    private final UUID customer = UUID.randomUUID();

    @BeforeEach
    void reset() {
        h.rateLimitedBuckets.clear();
        h.rateLimitCalls.clear();
    }

    private UUID book(long stock) {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "Sach " + id, "Tac gia", "ISBN-" + id,
                BigDecimal.valueOf(100_000), "Mo ta", true));
        h.registerItem.execute(TENANT, UUID.randomUUID(), id, null, null);
        h.adjust.execute(TENANT, UUID.randomUUID(), id, stock, "ton dau");
        return id;
    }

    private String body(UUID title, int qty, UUID requestId) {
        return "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":" + qty + "}],\"fulfilment\":\"PICKUP\","
                + "\"paymentMethod\":\"BANK_TRANSFER\",\"recipient\":{\"name\":\"Nguyen Van An\","
                + "\"phone\":\"0912345678\",\"address\":\"12 Le Loi\"},\"clientRequestId\":\"" + requestId + "\"}";
    }

    @Test
    void donBinhThuongQuaGioiHanVaGoiDungBucketTheoKhachRoiTenant() throws Exception {
        UUID title = book(10);

        mvc.perform(post(SHOP).with(userWithKeys(customer, SHOP_KEYS)).contentType(MediaType.APPLICATION_JSON)
                .content(body(title, 1, UUID.randomUUID()))).andExpect(status().isCreated());

        assertThat(h.rateLimitCalls).hasSize(2);
        assertThat(h.rateLimitCalls.get(0)).startsWith("order.create.user|" + customer + "|");
        assertThat(h.rateLimitCalls.get(1)).startsWith("order.create.tenant|tenant|");
    }

    @Test
    void khachDatQuaNhanhBi429VaKhongTaoDonKhongGiuHang() throws Exception {
        UUID title = book(10);
        int before = h.orderCount();
        h.rateLimitedBuckets.add("order.create.user");

        mvc.perform(post(SHOP).with(userWithKeys(customer, SHOP_KEYS)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(title, 1, UUID.randomUUID())))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ORDER_RATE_LIMITED"));

        assertThat(h.orderCount()).isEqualTo(before);
        assertThat(h.item(title).reserved()).isZero();
        // Bị chặn ở bước khách thì không đốt hạn mức chung của tenant
        assertThat(h.rateLimitCalls).hasSize(1);
    }

    @Test
    void tenantVuotHanMucChungCungBi429() throws Exception {
        UUID title = book(10);
        h.rateLimitedBuckets.add("order.create.tenant");

        mvc.perform(post(SHOP).with(userWithKeys(customer, SHOP_KEYS)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(title, 1, UUID.randomUUID())))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ORDER_RATE_LIMITED"));
    }

    @Test
    void cungKhoaKhacNoiDungBi422() throws Exception {
        UUID title = book(10);
        UUID cid = UUID.randomUUID();
        mvc.perform(post(SHOP).with(userWithKeys(customer, SHOP_KEYS)).contentType(MediaType.APPLICATION_JSON)
                .content(body(title, 1, cid))).andExpect(status().isCreated());

        mvc.perform(post(SHOP).with(userWithKeys(customer, SHOP_KEYS)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(title, 2, cid)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        // gửi lại đúng nội dung thì vẫn trả đơn cũ
        mvc.perform(post(SHOP).with(userWithKeys(customer, SHOP_KEYS)).contentType(MediaType.APPLICATION_JSON)
                .content(body(title, 1, cid))).andExpect(status().isCreated());
    }

    @Test
    void vuotTranSoLuongTheoChinhSachLa400() throws Exception {
        UUID title = book(500);

        mvc.perform(post(SHOP).with(userWithKeys(customer, SHOP_KEYS)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(title, 51, UUID.randomUUID()))) // mặc định 50 mỗi dòng
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
    }

    @Test
    void chinhSachMacDinhRoiDoiVaCoKhoaLacQuan() throws Exception {
        mvc.perform(get(POLICY).with(admin(java.util.List.of("SALES")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationMinutes").value(15))
                .andExpect(jsonPath("$.maxPendingOrders").value(5))
                .andExpect(jsonPath("$.version").value(0));

        String update = "{\"reservationMinutes\":30,\"maxPendingOrders\":2,\"maxOrdersPerDay\":10,"
                + "\"maxQuantityPerLine\":5,\"maxTotalQuantity\":20,\"ordersPerMinutePerUser\":3,"
                + "\"ordersPerMinuteTenant\":100,\"version\":0}";
        mvc.perform(put(POLICY).with(admin(java.util.List.of("SALES"))).contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reservationMinutes").value(30))
                .andExpect(jsonPath("$.version").value(1));

        // version cũ bị từ chối
        mvc.perform(put(POLICY).with(admin(java.util.List.of("SALES"))).contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTINGS_VERSION_CONFLICT"));

        // giá trị ngoài khoảng hợp lệ là 400
        mvc.perform(put(POLICY).with(admin(java.util.List.of("SALES"))).contentType(MediaType.APPLICATION_JSON)
                        .content(update.replace("\"maxPendingOrders\":2", "\"maxPendingOrders\":0")
                                .replace("\"version\":0", "\"version\":1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SETTINGS"));
    }

    @Test
    void khongCoKhoaThiKhongDoiDuocChinhSach() throws Exception {
        mvc.perform(put(POLICY).with(withKey("ShopController/placeOrder")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationMinutes\":1,\"maxPendingOrders\":1,\"maxOrdersPerDay\":1,"
                                + "\"maxQuantityPerLine\":1,\"maxTotalQuantity\":1,\"ordersPerMinutePerUser\":1,"
                                + "\"ordersPerMinuteTenant\":1,\"version\":0}"))
                .andExpect(status().isForbidden());
    }
}
