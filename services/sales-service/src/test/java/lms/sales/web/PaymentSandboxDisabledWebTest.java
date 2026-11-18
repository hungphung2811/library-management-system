package lms.sales.web;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.USER;
import static lms.sales.support.WebTokens.withKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.ordering.infrastructure.web.PaymentIntentController;
import lms.sales.ordering.infrastructure.web.PaymentWebhookController;
import lms.sales.ordering.infrastructure.web.PosOrderController;
import lms.sales.ordering.infrastructure.web.SimulatedWebhookProcessor;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.support.Harness;
import lms.sales.support.HarnessBeans;
import lms.sales.support.QrFixture;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sandbox thanh toán tắt (mặc định ở prod, ADR-0020): webhook và nút giả lập trả 404 (kể cả khi chữ ký đúng), POS từ
 * chối paymentMethod QR bằng 400 PAYMENT_NOT_ENABLED, còn các phương thức khác không đổi.
 */
@WebMvcTest({PosOrderController.class, PaymentIntentController.class, PaymentWebhookController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class, SimulatedWebhookProcessor.class, PaymentSandboxDisabledWebTest.Extra.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true",
        "app.payments.sandbox.enabled=false", "app.payments.sandbox.webhook-secret=test-secret"})
class PaymentSandboxDisabledWebTest {

    /** HarnessBeans đã có PlacePosOrderUseCase không QR; thêm phần còn thiếu của thanh toán. */
    @org.springframework.boot.test.context.TestConfiguration
    static class Extra {
        @Bean
        QrFixture qrFixture(Harness h) {
            return new QrFixture(h);
        }

        @Bean
        lms.sales.ordering.application.PaymentIntentUseCases paymentIntentUseCases(QrFixture fx) {
            return fx.intentUseCases;
        }

        @Bean
        lms.sales.ordering.application.ProcessPaymentWebhookUseCase processPaymentWebhookUseCase(QrFixture fx) {
            return fx.webhook;
        }

        @Bean
        lms.sales.ordering.application.SyncGatewayPaymentUseCase syncGatewayPaymentUseCase(QrFixture fx) {
            return fx.gatewaySync;
        }

        @Bean
        lms.sales.ordering.application.PaymentTenantResolverPort paymentTenantResolverPort(QrFixture fx) {
            return fx.resolver;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    Harness h;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void webhookVaNutGiaLapTra404KhiSandboxTat() throws Exception {
        mvc.perform(post("/internal/payments/webhook/simulated").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "abc").content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_SANDBOX_DISABLED"));
        mvc.perform(post("/internal/payments/intents/" + UUID.randomUUID() + "/simulate-paid")
                        .with(withKey("PaymentIntentController/simulatePaid")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_SANDBOX_DISABLED"));
    }

    @Test
    void posTuChoiQrKhiChuaBatNhungVanBanTienMat() throws Exception {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "Sach", "TG", "ISBN-" + id, BigDecimal.valueOf(100_000)));
        h.registerItem.execute(TENANT, USER, id, null, null);
        h.adjust.execute(TENANT, USER, id, 5, "ton dau");
        String qr = "{\"lines\":[{\"titleId\":\"" + id + "\",\"quantity\":1}],\"paymentMethod\":\"QR\","
                + "\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(post("/internal/sales/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(qr))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_ENABLED"));
        mvc.perform(post("/internal/sales/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(qr.replace("QR", "CASH")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.paymentIntent.provider").value("MANUAL"));
    }
}
