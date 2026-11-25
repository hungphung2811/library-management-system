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
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.stock.application.AdjustStockUseCase;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 * Bất thường thanh toán (ADR-0029) trên PostgreSQL thật bằng vai trò sales_app (NOBYPASSRLS): audit PAYMENT_ANOMALY được
 * ghi sau khi transaction nghiệp vụ rollback, trong transaction RIÊNG (REQUIRES_NEW) và đúng tenant (app.tenant_id của
 * kết nối mới), không rò sang tenant khác, tối đa một dòng cho mỗi (lệnh, loại), chữ ký sai không ghi audit, đường
 * thăm dò bình thường không ghi DB. Cần Docker: mvn -o -pl services/sales-service -am test -Dtest=PaymentAnomalyIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44.
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
@Import(PaymentAnomalyIT.PollingGateway.class)
class PaymentAnomalyIT {

    private static final String SECRET = "it-secret";
    private static final String HOOK = "/internal/payments/webhook/simulated";
    private static final String INTENTS = "/internal/payments/intents";

    /** Cổng SEPAY giả cho đường thăm dò: đếm lời gọi, trả giao dịch theo cấu hình của từng test. */
    @TestConfiguration
    static class PollingGateway {
        static final AtomicLong QUERIES = new AtomicLong();
        /** Số tiền cổng báo (null = không có giao dịch). */
        static volatile Long reportAmount;

        @Bean
        PaymentGatewayPort sepayStubGateway() {
            return new PaymentGatewayPort() {
                @Override
                public String provider() {
                    return "SEPAY";
                }

                @Override
                public QrCharge createQr(ChargeRequest request) {
                    return new QrCharge("QR-" + request.reference());
                }

                @Override
                public Optional<GatewayPayment> queryPayment(String reference, long amount, Instant notBefore) {
                    QUERIES.incrementAndGet();
                    Long reported = reportAmount;
                    return reported == null ? Optional.empty()
                            : Optional.of(new GatewayPayment(true, "SEPAY-IT-" + reference, reported));
                }
            };
        }
    }

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
    @Autowired PlatformTransactionManager txManager;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean CatalogLookupPort catalog;

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID user = UUID.randomUUID();

    @BeforeEach
    void stubCatalog() {
        PollingGateway.reportAmount = null;
        PollingGateway.QUERIES.set(0);
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
        TenantContext.runAs(tenant, () -> new TransactionTemplate(txManager).executeWithoutResult(t -> {
            registerItem.execute(tenant, user, id, null, null);
            adjust.execute(tenant, user, id, stock, "ton dau");
        }));
        return id;
    }

    private JsonNode createOrder(UUID tenant, UUID title, int qty, String method) throws Exception {
        String body = "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":" + qty + "}],"
                + "\"paymentMethod\":\"" + method + "\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
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
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private long anomalyRows(UUID tenant, String intentId, String type) throws SQLException {
        return scalar("select count(*) from sales_audit_log where tenant_id = '" + tenant
                + "' and action = 'PAYMENT_ANOMALY' and aggregate_id = '" + intentId + "'"
                + (type == null ? "" : " and data->>'anomalyType' = '" + type + "'"));
    }

    private long stock(UUID tenant, UUID title, String column) throws SQLException {
        return scalar("select " + column + " from stock_items where tenant_id = '" + tenant + "' and title_id = '"
                + title + "'");
    }

    @Test
    void lechTienRollbackNhungAuditAnomalyConLaiDungTenantVaChiMotDong() throws Exception {
        UUID title = stocked(tenantA, 100_000, 10);
        JsonNode order = createOrder(tenantA, title, 2, "QR");
        String intentId = order.get("paymentIntent").get("id").asText();
        String reference = order.get("paymentIntent").get("reference").asText();
        long orderPaidBefore = scalar("select count(*) from sales_audit_log where tenant_id = '" + tenantA
                + "' and action = 'ORDER_PAID'");

        String wrong = hook(reference, 1, "IT-ANOM-1");
        // Lặp lại: HTTP không đổi (400 PAYMENT_AMOUNT_MISMATCH), audit chỉ một dòng cho (lệnh, loại)
        for (int n = 0; n < 3; n++) {
            callHook(wrong, sign(wrong)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        }

        // Transaction nghiệp vụ đã rollback: lệnh vẫn PENDING, tồn giữ nguyên, không có ORDER_PAID mới
        assertThat(text("select status from payment_intents where id = '" + intentId + "'")).isEqualTo("PENDING");
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(2);
        assertThat(stock(tenantA, title, "on_hand")).isEqualTo(10);
        assertThat(scalar("select count(*) from sales_audit_log where tenant_id = '" + tenantA
                + "' and action = 'ORDER_PAID'")).isEqualTo(orderPaidBefore);

        // Audit anomaly được ghi (REQUIRES_NEW), đúng tenant A, một dòng
        assertThat(anomalyRows(tenantA, intentId, "PAYMENT_AMOUNT_MISMATCH")).isEqualTo(1);
        assertThat(scalar("select count(*) from sales_audit_log where action = 'PAYMENT_ANOMALY' and tenant_id = '"
                + tenantB + "'")).as("không dòng nào ở tenant khác").isEqualTo(0);
        assertThat(text("select data->>'receivedAmount' || '/' || (data->>'expectedAmount') || '/' "
                + "|| (data->>'source') || '/' || (data->>'reference') from sales_audit_log where aggregate_id = '"
                + intentId + "' and action = 'PAYMENT_ANOMALY'")).isEqualTo("1/200000/WEBHOOK/" + reference);
        assertThat(text("select coalesce(actor_id::text, 'null') from sales_audit_log where aggregate_id = '"
                + intentId + "' and action = 'PAYMENT_ANOMALY'")).isEqualTo("null");

        // Tenant A thấy dòng trên màn Nhật ký (lọc action); tenant B thì không
        mvc.perform(get("/internal/sales/audit-log").param("action", "PAYMENT_ANOMALY").with(admin(tenantA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].data.anomalyType").value("PAYMENT_AMOUNT_MISMATCH"));
        mvc.perform(get("/internal/sales/audit-log").param("action", "PAYMENT_ANOMALY").with(admin(tenantB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));

        // Sau đó thanh toán đúng vẫn thành công (rollback không để lại dấu vết)
        String ok = hook(reference, 200_000, "IT-ANOM-OK");
        callHook(ok, sign(ok)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PROCESSED"));
        assertThat(text("select status from payment_intents where id = '" + intentId + "'")).isEqualTo("PAID");
    }

    @Test
    void chuKySaiKhongGhiAuditNaoVaKhongDoiGi() throws Exception {
        UUID title = stocked(tenantA, 100_000, 10);
        JsonNode pi = createOrder(tenantA, title, 1, "QR").get("paymentIntent");
        String intentId = pi.get("id").asText();
        long auditBefore = scalar("select count(*) from sales_audit_log");

        String body = hook(pi.get("reference").asText(), 100_000, "IT-BAD-SIG");
        callHook(body, "00" + sign(body).substring(2)).andExpect(status().isUnauthorized());
        callHook(body, "").andExpect(status().isUnauthorized());
        callHook("x".repeat(5000), "sig").andExpect(status().isBadRequest());
        String unknown = hook("LMSZZZZZZZZZZ", 1000, "IT-UNKNOWN");
        callHook(unknown, sign(unknown)).andExpect(status().isNotFound());

        assertThat(scalar("select count(*) from sales_audit_log")).isEqualTo(auditBefore);
        assertThat(scalar("select count(*) from sales_audit_log where action = 'PAYMENT_ANOMALY'")).isEqualTo(0);
        assertThat(text("select status from payment_intents where id = '" + intentId + "'")).isEqualTo("PENDING");
    }

    @Test
    void tienVeSauKhiHetHanVaThuHaiLanDeuCoAudit() throws Exception {
        UUID title = stocked(tenantA, 100_000, 10);
        JsonNode first = createOrder(tenantA, title, 1, "QR").get("paymentIntent");
        String expiredId = first.get("id").asText();
        exec("update payment_intents set expires_at = now() - interval '1 minute' where id = '" + expiredId + "'");
        String lateBody = hook(first.get("reference").asText(), 100_000, "IT-LATE");
        callHook(lateBody, sign(lateBody)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_EXPIRED"));
        assertThat(anomalyRows(tenantA, expiredId, "PAYMENT_AFTER_EXPIRY")).isEqualTo(1);

        JsonNode second = createOrder(tenantA, title, 1, "QR").get("paymentIntent");
        String paidId = second.get("id").asText();
        String once = hook(second.get("reference").asText(), 100_000, "IT-FIRST");
        callHook(once, sign(once)).andExpect(status().isOk());
        String twice = hook(second.get("reference").asText(), 100_000, "IT-SECOND");
        callHook(twice, sign(twice)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_STATE_CONFLICT"));
        assertThat(anomalyRows(tenantA, paidId, "PAYMENT_DOUBLE_PAID")).isEqualTo(1);
        // Gửi lại đúng mã giao dịch cũ là DUPLICATE (không phải bất thường)
        callHook(once, sign(once)).andExpect(status().isOk());
        assertThat(anomalyRows(tenantA, paidId, null)).isEqualTo(1);
    }

    @Test
    void thamDoSepayBinhThuongKhongGhiDbVaLechTienThiGhiAuditTrongTenantCuaRequest() throws Exception {
        UUID title = stocked(tenantA, 100_000, 10);
        JsonNode order = createOrder(tenantA, title, 1, "SEPAY");
        JsonNode pi = order.get("paymentIntent");
        String intentId = pi.get("id").asText();
        assertThat(pi.get("provider").asText()).isEqualTo("SEPAY");

        // Không có giao dịch: nhiều lần thăm dò không đổi gì trong DB (xmin của dòng lệnh không đổi, không có audit mới)
        String xminBefore = text("select xmin::text from payment_intents where id = '" + intentId + "'");
        long auditBefore = scalar("select count(*) from sales_audit_log where tenant_id = '" + tenantA + "'");
        for (int n = 0; n < 20; n++) {
            mvc.perform(get(INTENTS + "/" + intentId).with(admin(tenantA))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }
        assertThat(PollingGateway.QUERIES.get()).isEqualTo(20);
        assertThat(text("select xmin::text from payment_intents where id = '" + intentId + "'"))
                .isEqualTo(xminBefore);
        assertThat(scalar("select count(*) from sales_audit_log where tenant_id = '" + tenantA + "'"))
                .isEqualTo(auditBefore);

        // Cổng báo lệch tiền: lần thăm dò vẫn trả 200 PENDING, audit anomaly (nguồn POLL) ghi đúng tenant của request
        PollingGateway.reportAmount = 5L;
        for (int n = 0; n < 5; n++) {
            mvc.perform(get(INTENTS + "/" + intentId).with(admin(tenantA))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }
        assertThat(anomalyRows(tenantA, intentId, "PAYMENT_AMOUNT_MISMATCH")).isEqualTo(1);
        assertThat(text("select data->>'source' from sales_audit_log where aggregate_id = '" + intentId
                + "' and action = 'PAYMENT_ANOMALY'")).isEqualTo("POLL");
        assertThat(text("select status from payment_intents where id = '" + intentId + "'")).isEqualTo("PENDING");
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(1);

        // Tenant khác không thăm dò được lệnh này và không có dòng audit nào
        mvc.perform(get(INTENTS + "/" + intentId).with(admin(tenantB))).andExpect(status().isNotFound());
        assertThat(scalar("select count(*) from sales_audit_log where tenant_id = '" + tenantB
                + "' and action = 'PAYMENT_ANOMALY'")).isEqualTo(0);

        // Cổng báo đúng tiền: PAID, một dòng ORDER_PAID, không thêm anomaly
        PollingGateway.reportAmount = 100_000L;
        mvc.perform(get(INTENTS + "/" + intentId).with(admin(tenantA))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(anomalyRows(tenantA, intentId, null)).isEqualTo(1);
    }
}
