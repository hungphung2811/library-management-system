package lms.sales.web;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.userWithKeys;
import static lms.sales.support.WebTokens.withKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.ordering.application.PaymentIntentResponse;
import lms.sales.ordering.application.ShopPaymentResponse;
import lms.sales.ordering.application.ShopPaymentUseCases;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.ordering.infrastructure.web.ShopPaymentController;
import lms.sales.ordering.infrastructure.web.ShopPaymentView;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP của thanh toán online cho khách (ADR-0029 mục 3.1) với kiểm khóa bật lại: hai endpoint {@code @AuthenticatedOnly}
 * (chỉ cần đăng nhập + tenant + module SALES, không khóa riêng), allowlist tên trường {@link ShopPaymentView}, qrPayload
 * chỉ khi PENDING, 404 cùng thân cho đơn không phải của mình.
 */
@WebMvcTest(ShopPaymentController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class ShopPaymentWebTest {

    private static final String B = "/internal/sales/shop";
    private static final Set<String> VIEW_FIELDS = Set.of("orderId", "orderNo", "orderStatus", "paymentStatus",
            "paymentMethod", "intent");
    private static final Set<String> INTENT_FIELDS = Set.of("status", "provider", "qrPayload", "amount", "currency",
            "reference", "expiresAt", "paidAt");

    private final UUID customer = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID intentId = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean ShopPaymentUseCases payments;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private PaymentIntentResponse intent(PaymentIntentStatus status) {
        Instant t = Instant.parse("2026-11-19T03:00:00Z");
        return new PaymentIntentResponse(intentId, orderId, "LMSABCDEFGHIJ", 130_000, status, "SEPAY", "QR-PAYLOAD",
                t.plusSeconds(900), status == PaymentIntentStatus.PAID ? t.plusSeconds(60) : null, t, 7654321L, 8765432L,
                "TREF-SECRET");
    }

    private ShopPaymentResponse response(PaymentIntentResponse i, OrderStatus os, PaymentStatus ps) {
        return new ShopPaymentResponse(orderId, "O-1", os, ps, PaymentMethod.SEPAY, i);
    }

    private String getJson(String path, org.springframework.test.web.servlet.request.RequestPostProcessor who)
            throws Exception {
        return mvc.perform(get(path).with(who)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    private static Set<String> names(JsonNode n) {
        Set<String> r = new HashSet<>();
        n.fieldNames().forEachRemaining(r::add);
        return r;
    }

    @Test
    void allowlistTenTruongCuaRecordViewKhopHopDong() {
        assertThat(Arrays.stream(ShopPaymentView.class.getRecordComponents()).map(RecordComponent::getName).toList())
                .containsExactlyInAnyOrderElementsOf(VIEW_FIELDS);
        assertThat(Arrays.stream(ShopPaymentView.Intent.class.getRecordComponents()).map(RecordComponent::getName)
                .toList()).containsExactlyInAnyOrderElementsOf(INTENT_FIELDS);
    }

    @Test
    void phanHoiChiCoTruongTrongAllowlistKhongLoIdLenhGiaoDichHayThongTinThuTay() throws Exception {
        when(payments.myOrderPayment(TENANT, customer, orderId)).thenReturn(response(
                intent(PaymentIntentStatus.PENDING), OrderStatus.AWAITING_PAYMENT, PaymentStatus.UNPAID));

        String body = getJson(B + "/orders/" + orderId + "/payment", userWithKeys(customer));

        JsonNode n = json.readTree(body);
        assertThat(names(n)).isEqualTo(VIEW_FIELDS);
        assertThat(names(n.get("intent"))).isEqualTo(INTENT_FIELDS);
        assertThat(n.get("orderStatus").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(n.get("paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(n.get("paymentMethod").asText()).isEqualTo("SEPAY");
        assertThat(n.get("intent").get("status").asText()).isEqualTo("PENDING");
        assertThat(n.get("intent").get("provider").asText()).isEqualTo("SEPAY");
        assertThat(n.get("intent").get("qrPayload").asText()).isEqualTo("QR-PAYLOAD");
        assertThat(n.get("intent").get("amount").asLong()).isEqualTo(130_000);
        assertThat(n.get("intent").get("currency").asText()).isEqualTo("VND");
        assertThat(n.get("intent").get("reference").asText()).isEqualTo("LMSABCDEFGHIJ");
        assertThat(body).doesNotContain(intentId.toString()).doesNotContain(TENANT.toString())
                .doesNotContain(customer.toString()).doesNotContain("TREF-SECRET").doesNotContain("7654321")
                .doesNotContain("8765432").doesNotContain("tenderedAmount").doesNotContain("changeDue")
                .doesNotContain("providerTxnId").doesNotContain("transactionRef").doesNotContain("createdAt")
                .doesNotContain("version");
        verify(payments).myOrderPayment(TENANT, customer, orderId);
    }

    @Test
    void qrPayloadChiCoKhiLenhPendingKhiDaTraHoacHetHanThiKhongTraMaQuet() throws Exception {
        for (PaymentIntentStatus s : List.of(PaymentIntentStatus.PAID, PaymentIntentStatus.EXPIRED,
                PaymentIntentStatus.CANCELLED)) {
            when(payments.myOrderPayment(TENANT, customer, orderId)).thenReturn(response(intent(s),
                    OrderStatus.PENDING_CONFIRMATION, PaymentStatus.PAID));
            JsonNode n = json.readTree(getJson(B + "/orders/" + orderId + "/payment", userWithKeys(customer)));
            JsonNode qr = n.get("intent").path("qrPayload");
            assertThat(qr.isNull() || qr.isMissingNode()).as("qrPayload khi " + s).isTrue();
            assertThat(n.get("intent").get("status").asText()).isEqualTo(s.name());
            assertThat(n.get("intent").get("reference").asText()).isEqualTo("LMSABCDEFGHIJ");
        }
    }

    @Test
    void donKhongQuaCongTraIntentNull() throws Exception {
        when(payments.myOrderPayment(TENANT, customer, orderId)).thenReturn(response(null,
                OrderStatus.PENDING_CONFIRMATION, PaymentStatus.UNPAID));
        JsonNode n = json.readTree(getJson(B + "/orders/" + orderId + "/payment", userWithKeys(customer)));
        assertThat(n.get("intent").isNull()).isTrue();
    }

    @Test
    void donCuaNguoiKhacHoacKhongCoTra404CungMaVaCungThan() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(payments.myOrderPayment(any(), any(), any()))
                .thenThrow(new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));

        String a = mvc.perform(get(B + "/orders/" + orderId + "/payment").with(userWithKeys(customer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND")).andReturn()
                .getResponse().getContentAsString();
        String b = mvc.perform(get(B + "/orders/" + unknown + "/payment").with(userWithKeys(customer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND")).andReturn()
                .getResponse().getContentAsString();

        assertThat(json.readTree(a).get("message")).isEqualTo(json.readTree(b).get("message"));
        assertThat(a).doesNotContain(orderId.toString());
    }

    @Test
    void thieuModuleSalesHoacThieuTenantBi403KhongGoiUseCase() throws Exception {
        mvc.perform(get(B + "/orders/" + orderId + "/payment").with(withKeys(TENANT, List.of("LENDING"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(B + "/orders/" + orderId + "/payment").with(withKeys(null, List.of("SALES"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(B + "/payment-options").with(withKeys(TENANT, List.of("LENDING"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(B + "/payment-options").with(withKeys(null, List.of("SALES"))))
                .andExpect(status().isForbidden());
        verify(payments, never()).myOrderPayment(any(), any(), any());
        verify(payments, never()).paymentOptions(any());
    }

    @Test
    void chuaDangNhapBi401() throws Exception {
        mvc.perform(get(B + "/orders/" + orderId + "/payment")).andExpect(status().isUnauthorized());
        mvc.perform(get(B + "/payment-options")).andExpect(status().isUnauthorized());
    }

    @Test
    void tuyChonThanhToanChiCanDangNhapKhongCanKhoaRiengVaSepayCoThoiHanLenh() throws Exception {
        when(payments.paymentOptions(TENANT)).thenReturn(new ShopPaymentUseCases.PaymentOptions(List.of(
                new ShopPaymentUseCases.PaymentOption(PaymentMethod.COD, List.of(Fulfilment.DELIVERY), null),
                new ShopPaymentUseCases.PaymentOption(PaymentMethod.CASH, List.of(Fulfilment.PICKUP), null),
                new ShopPaymentUseCases.PaymentOption(PaymentMethod.BANK_TRANSFER,
                        List.of(Fulfilment.PICKUP, Fulfilment.DELIVERY), null),
                new ShopPaymentUseCases.PaymentOption(PaymentMethod.SEPAY,
                        List.of(Fulfilment.PICKUP, Fulfilment.DELIVERY), 15)), 30_000));

        JsonNode n = json.readTree(getJson(B + "/payment-options", userWithKeys(customer))); // không có khóa nào

        assertThat(names(n)).isEqualTo(Set.of("methods", "deliveryFee"));
        assertThat(n.get("deliveryFee").asLong()).isEqualTo(30_000);
        JsonNode methods = n.get("methods");
        assertThat(methods).hasSize(4);
        assertThat(methods.get(0).get("method").asText()).isEqualTo("COD");
        assertThat(names(methods.get(0))).isEqualTo(Set.of("method", "fulfilments"));
        assertThat(methods.get(0).get("fulfilments").get(0).asText()).isEqualTo("DELIVERY");
        assertThat(methods.get(3).get("method").asText()).isEqualTo("SEPAY");
        assertThat(names(methods.get(3))).isEqualTo(Set.of("method", "fulfilments", "intentTtlMinutes"));
        assertThat(methods.get(3).get("intentTtlMinutes").asInt()).isEqualTo(15);
    }
}
