package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lms.sales.ordering.application.ExpirePaymentIntentsUseCase;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IT thanh toán QR (ADR-0020) bằng vai trò sales_app (NOBYPASSRLS) trên PostgreSQL thật: tạo đơn QR giữ tồn, webhook
 * công khai (không JWT) tra tenant qua phạm vi đọc tất cả rồi ghi trong đúng tenant, trùng mã giao dịch, hết hạn do job,
 * hủy, cách ly tenant, và hai webhook đồng thời chỉ trừ tồn một lần. Cần Docker: mvn -o -pl services/sales-service -am
 * test -Dtest=PaymentQrIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false",
        "app.payments.sandbox.enabled=true",
        "app.payments.sandbox.webhook-secret=it-secret",
        "app.payments.expire-job-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class PaymentQrIT {

    private static final String SECRET = "it-secret";
    private static final String HOOK = "/internal/payments/webhook/simulated";
    private static final String INTENTS = "/internal/payments/intents";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    static final Map<UUID, CatalogLookupPort.CatalogTitle> TITLES = new ConcurrentHashMap<>();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RegisterStockItemUseCase registerItem;
    @Autowired AdjustStockUseCase adjust;
    @Autowired ReconcileStockUseCase reconcile;
    @Autowired ExpirePaymentIntentsUseCase expire;
    @Autowired PlatformTransactionManager txManager;

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
        return jwt().jwt(j -> j.subject(user.toString()).claim("admin", true).claim("modules", List.of("SALES"))
                .claim("tenantId", tenant.toString()));
    }

    private UUID stocked(UUID tenant, long coverPrice, long stock) {
        UUID id = UUID.randomUUID();
        TITLES.put(id, new CatalogLookupPort.CatalogTitle(id, "Sach " + id.toString().substring(0, 6), "Tac gia",
                "ISBN-" + id.toString().substring(0, 8), BigDecimal.valueOf(coverPrice)));
        // Trong một transaction tường minh: không phụ thuộc việc use case tự mở transaction hay chỉ có ở overload khác
        TenantContext.runAs(tenant, () -> new TransactionTemplate(txManager).executeWithoutResult(t -> {
            registerItem.execute(tenant, user, id, null, null);
            adjust.execute(tenant, user, id, stock, "ton dau");
        }));
        return id;
    }

    private JsonNode createQr(UUID tenant, UUID title, int qty) throws Exception {
        String body = "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":" + qty + "}],"
                + "\"paymentMethod\":\"QR\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        var r = mvc.perform(post("/internal/sales/pos-orders").with(admin(tenant))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hook(String reference, long amount, String txn) {
        return "{\"reference\":\"" + reference + "\",\"amount\":" + amount + ",\"providerTxnId\":\"" + txn
                + "\",\"status\":\"PAID\"}";
    }

    /** Gọi webhook như cổng thật: KHÔNG JWT, chỉ chữ ký. */
    private ResultActions callHook(String body, String signature) throws Exception {
        return mvc.perform(post(HOOK).contentType(MediaType.APPLICATION_JSON).header("X-Signature", signature)
                .content(body));
    }

    private long scalar(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String text(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private long stock(UUID tenant, UUID title, String column) throws SQLException {
        return scalar("select " + column + " from stock_items where tenant_id = '" + tenant + "' and title_id = '"
                + title + "'");
    }

    private void assertReconciled(UUID tenant, UUID title) {
        var r = TenantContext.callAs(tenant, () -> reconcile.execute(tenant, title));
        assertThat(r.consistent()).as("on_hand/reserved phải bằng tổng sổ cái: " + r).isTrue();
    }

    @Test
    void luongTaoDonWebhookTrungVaCachLyTenant() throws Exception {
        UUID title = stocked(tenantA, 100_000, 10);
        JsonNode order = createQr(tenantA, title, 2);
        JsonNode pi = order.get("paymentIntent");
        String intentId = pi.get("id").asText();
        String reference = pi.get("reference").asText();

        assertThat(order.get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(2);
        assertThat(stock(tenantA, title, "on_hand")).isEqualTo(10);
        assertThat(text("select status from payment_intents where id = '" + intentId + "'")).isEqualTo("PENDING");

        // Tenant khác không thấy lệnh (RLS + @TenantId)
        mvc.perform(get(INTENTS + "/" + intentId).with(admin(tenantB))).andExpect(status().isNotFound());
        mvc.perform(post(INTENTS + "/" + intentId + "/cancel").with(admin(tenantB))).andExpect(status().isNotFound());
        mvc.perform(post(INTENTS + "/" + intentId + "/simulate-paid").with(admin(tenantB)))
                .andExpect(status().isNotFound());
        mvc.perform(get(INTENTS + "/" + intentId).with(admin(tenantA))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // Chữ ký sai: 401, không đổi gì
        String body = hook(reference, 200_000, "IT-TXN-1");
        callHook(body, "00" + sign(body).substring(2)).andExpect(status().isUnauthorized());
        assertThat(text("select status from payment_intents where id = '" + intentId + "'")).isEqualTo("PENDING");

        // Lệch tiền: 400
        String wrong = hook(reference, 1, "IT-TXN-1");
        callHook(wrong, sign(wrong)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));

        // Đúng chữ ký, không JWT: tra tenant qua phạm vi ALL, ghi trong tenant của lệnh
        callHook(body, sign(body)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PROCESSED"));
        assertThat(text("select status from payment_intents where id = '" + intentId + "'")).isEqualTo("PAID");
        assertThat(text("select status || '/' || payment_status from sales_orders where id = '"
                + order.get("id").asText() + "'")).isEqualTo("COMPLETED/PAID");
        assertThat(text("select method || '/' || reference from payment_records where order_id = '"
                + order.get("id").asText() + "'")).isEqualTo("QR/" + reference);
        assertThat(stock(tenantA, title, "on_hand")).isEqualTo(8);
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(0);
        assertReconciled(tenantA, title);

        // Trùng mã giao dịch: idempotent
        callHook(body, sign(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ALREADY_PROCESSED"));
        assertThat(stock(tenantA, title, "on_hand")).isEqualTo(8);
        assertThat(scalar("select count(*) from payment_records where order_id = '" + order.get("id").asText() + "'"))
                .isEqualTo(1);

        // Đã thu thì không hủy được
        mvc.perform(post(INTENTS + "/" + intentId + "/cancel").with(admin(tenantA))).andExpect(status().isConflict());
    }

    @Test
    void jobHetHanDonCancelledTraTonVaWebhookMuonBiTuChoi() throws Exception {
        UUID title = stocked(tenantA, 100_000, 5);
        JsonNode pi = createQr(tenantA, title, 3).get("paymentIntent");
        String intentId = pi.get("id").asText();
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(3);

        exec("update payment_intents set expires_at = now() - interval '1 minute' where id = '" + intentId + "'");
        int expired = TenantContext.callAs(tenantA, () -> expire.execute(tenantA));

        assertThat(expired).isEqualTo(1);
        assertThat(text("select status from payment_intents where id = '" + intentId + "'")).isEqualTo("EXPIRED");
        assertThat(text("select status from sales_orders where id = '" + pi.get("orderId").asText() + "'"))
                .isEqualTo("CANCELLED");
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(0);
        assertThat(stock(tenantA, title, "on_hand")).isEqualTo(5);
        assertReconciled(tenantA, title);

        String body = hook(pi.get("reference").asText(), 300_000, "IT-TXN-LATE");
        callHook(body, sign(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_EXPIRED"));
        // Chạy lại job không làm gì thêm
        assertThat(TenantContext.callAs(tenantA, () -> expire.execute(tenantA))).isZero();
    }

    @Test
    void huyLenhNhaTonVaNutGiaLapDiDungLuongWebhook() throws Exception {
        UUID title = stocked(tenantA, 100_000, 5);
        JsonNode first = createQr(tenantA, title, 2).get("paymentIntent");
        mvc.perform(post(INTENTS + "/" + first.get("id").asText() + "/cancel").with(admin(tenantA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(0);
        String late = hook(first.get("reference").asText(), 200_000, "IT-TXN-CANCELLED");
        callHook(late, sign(late)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_CANCELLED"));

        JsonNode second = createQr(tenantA, title, 1).get("paymentIntent");
        mvc.perform(post(INTENTS + "/" + second.get("id").asText() + "/simulate-paid").with(admin(tenantA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(stock(tenantA, title, "on_hand")).isEqualTo(4);
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(0);
        assertReconciled(tenantA, title);
    }

    @Test
    void haiWebhookDongThoiChiTruTonMotLan() throws Exception {
        UUID title = stocked(tenantA, 100_000, 5);
        JsonNode pi = createQr(tenantA, title, 2).get("paymentIntent");
        String body = hook(pi.get("reference").asText(), 200_000, "IT-TXN-RACE");
        String sig = sign(body);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = List.of(
                    pool.submit(() -> { go.await(); return callHook(body, sig).andReturn().getResponse().getStatus(); }),
                    pool.submit(() -> { go.await(); return callHook(body, sig).andReturn().getResponse().getStatus(); }));
            go.countDown();
            int ok = 0;
            for (Future<Integer> f : results) {
                int s = f.get(30, TimeUnit.SECONDS);
                assertThat(s).as("bên thua nhận 200 (đã xử lý) hoặc 409 (xung đột phiên bản), không 5xx").isIn(200, 409);
                if (s == 200) {
                    ok++;
                }
            }
            assertThat(ok).isGreaterThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stock(tenantA, title, "on_hand")).isEqualTo(3);
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(0);
        assertThat(scalar("select count(*) from payment_records where order_id = '" + pi.get("orderId").asText()
                + "'")).isEqualTo(1);
        assertReconciled(tenantA, title);
    }

    private JsonNode createCash(UUID tenant, UUID title, int qty, String method) throws Exception {
        String body = "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":" + qty + "}],"
                + "\"paymentMethod\":\"" + method + "\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        var r = mvc.perform(post("/internal/sales/pos-orders").with(admin(tenant))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void posHaiBuocThuTayXacNhanTienMatTheVaJobHetHanLenhManual() throws Exception {
        UUID title = stocked(tenantA, 100_000, 10);

        // Tiền mặt: chờ thu, giữ tồn, chưa doanh thu; xác nhận thì xuất kho, ghi bản ghi thu CASH, tiền thối
        JsonNode cash = createCash(tenantA, title, 2, "CASH");
        assertThat(cash.get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(cash.get("paymentIntent").get("provider").asText()).isEqualTo("MANUAL");
        String orderId = cash.get("id").asText();
        String intentId = cash.get("paymentIntent").get("id").asText();
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(2);
        assertThat(scalar("select count(*) from sales_orders where id = '" + orderId + "' and paid_at is not null"))
                .isZero();
        mvc.perform(get("/internal/sales/orders/" + orderId + "/receipt").with(admin(tenantA)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PAID"));
        mvc.perform(post(INTENTS + "/" + intentId + "/confirm").with(admin(tenantB))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(post(INTENTS + "/" + intentId + "/confirm").with(admin(tenantA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenderedAmount\":250000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.changeDue").value(50000));
        assertThat(text("select status || '/' || payment_status from sales_orders where id = '" + orderId + "'"))
                .isEqualTo("COMPLETED/PAID");
        assertThat(text("select method || '/' || recorded_by from payment_records where order_id = '" + orderId + "'"))
                .isEqualTo("CASH/" + user);
        assertThat(text("select tendered_amount || '/' || change_due from payment_intents where id = '" + intentId
                + "'")).isEqualTo("250000/50000");
        assertThat(stock(tenantA, title, "on_hand")).isEqualTo(8);
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(0);
        mvc.perform(post(INTENTS + "/" + intentId + "/confirm").with(admin(tenantA))).andExpect(status().isOk());
        assertThat(scalar("select count(*) from payment_records where order_id = '" + orderId + "'")).isEqualTo(1);
        mvc.perform(get("/internal/sales/orders/" + orderId + "/receipt").with(admin(tenantA)))
                .andExpect(status().isOk());

        // Thẻ: bắt buộc mã giao dịch
        JsonNode card = createCash(tenantA, title, 1, "CARD");
        String cardIntent = card.get("paymentIntent").get("id").asText();
        mvc.perform(post(INTENTS + "/" + cardIntent + "/confirm").with(admin(tenantA))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(INTENTS + "/" + cardIntent + "/confirm").with(admin(tenantA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"transactionRef\":\"POS-9\"}"))
                .andExpect(status().isOk());
        assertThat(text("select method || '/' || reference from payment_records where order_id = '"
                + card.get("id").asText() + "'")).isEqualTo("CARD/POS-9");

        // Lệnh MANUAL hết hạn: job nhả tồn, đơn CANCELLED; xác nhận muộn bị 409; webhook không xác nhận được lệnh MANUAL
        JsonNode late = createCash(tenantA, title, 3, "BANK_TRANSFER");
        String lateIntent = late.get("paymentIntent").get("id").asText();
        String lateRef = late.get("paymentIntent").get("reference").asText();
        String hookBody = hook(lateRef, 300_000, "IT-TXN-MANUAL");
        callHook(hookBody, sign(hookBody)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_SIMULATED"));
        exec("update payment_intents set expires_at = now() - interval '1 minute' where id = '" + lateIntent + "'");
        assertThat(TenantContext.callAs(tenantA, () -> expire.execute(tenantA))).isEqualTo(1);
        assertThat(text("select status from sales_orders where id = '" + late.get("id").asText() + "'"))
                .isEqualTo("CANCELLED");
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(0);
        mvc.perform(post(INTENTS + "/" + lateIntent + "/confirm").with(admin(tenantA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"transactionRef\":\"X\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_INTENT_STATE_CONFLICT"));
        assertReconciled(tenantA, title);
    }
}
