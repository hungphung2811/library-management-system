package lms.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lms.customer.shop.client.ShopInvoiceClient;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceBuyerRequest;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceRequestBody;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceRequestStatusView;
import lms.customer.shop.web.InvoiceRequestController;
import lms.customer.config.SecurityConfiguration;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Yêu cầu xuất hóa đơn của khách (ADR-0030 gói A): cần đăng nhập storefront, kiểm phiên theo {code}, chuyển token NGƯỜI DÙNG,
 * thân allowlist, phản hồi chỉ có trạng thái, lỗi qua danh sách cho phép (IDOR: 404 ORDER_NOT_FOUND cùng thân).
 */
@WebMvcTest(InvoiceRequestController.class)
@Import({SecurityConfiguration.class, PublicExceptionHandler.class, ShopSessionGuard.class})
class InvoiceRequestWebTest {

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean ShopInvoiceClient invoices;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String BODY = """
            {"buyer":{"name":"Nguyễn Văn An","company":"Công ty A","taxCode":"0123456789","address":"1 Lê Lợi","email":"an@a.vn"}}""";

    @BeforeEach
    void shopA() {
        when(storefronts.resolve("shop-a")).thenReturn(
                new StorefrontAccess(tenantA, "shop-a", "Hiệu sách A", "svc-token-a", 300));
    }

    private RequestPostProcessor customer(UUID tenant) {
        return jwt().jwt(j -> j.tokenValue("user-token").subject("u1").claim("tenantId", tenant.toString())
                .audience(List.of("lms-storefront")));
    }

    private static HttpClientErrorException downstream(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private String url() {
        return "/public/t/shop-a/orders/" + orderId + "/invoice-request";
    }

    @Test
    void khongTokenLa401KhongGoiSales() throws Exception {
        mvc.perform(put(url()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(invoices, storefronts);
    }

    @Test
    void tokenDichVuLa401VaTokenCuaHangKhacLa403() throws Exception {
        mvc.perform(put(url()).with(jwt().jwt(j -> j.tokenValue("svc").claim("svc", "customer-api")
                        .claim("tenantId", tenantA.toString()).audience(List.of("lms-storefront"))))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(put(url()).with(customer(tenantB)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SHOP_SESSION_MISMATCH"));
        verifyNoInteractions(invoices);
    }

    @Test
    void chuyenTokenNguoiDungThanAllowlistVaPhanHoiChiCoTrangThai() throws Exception {
        when(invoices.saveInvoiceRequest(eq("user-token"), eq(orderId), any()))
                .thenReturn(new InvoiceRequestStatusView(orderId, "OPEN"));
        // trường lạ (tenantId, orderId, status, price) bị bỏ qua và không tới sales
        String body = BODY.replace("{\"buyer\":{", "{\"tenantId\":\"x\",\"status\":\"FULFILLED\",\"buyer\":{\"internal\":1,");
        String json = mvc.perform(put(url()).with(customer(tenantA)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.orderId").value(orderId.toString())).andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("Nguyễn Văn An").doesNotContain("an@a.vn").doesNotContain("0123456789");
        ArgumentCaptor<InvoiceRequestBody> captor = ArgumentCaptor.forClass(InvoiceRequestBody.class);
        verify(invoices).saveInvoiceRequest(eq("user-token"), eq(orderId), captor.capture());
        InvoiceBuyerRequest b = captor.getValue().buyer();
        assertThat(b.company()).isEqualTo("Công ty A");
        assertThat(b.taxCode()).isEqualTo("0123456789");
        assertThat(captor.getValue().toString()).doesNotContain("Nguyễn").doesNotContain("an@a.vn");
    }

    @Test
    void orderIdSaiDinhDangLa404CungThanKhongGoiSales() throws Exception {
        mvc.perform(put("/public/t/shop-a/orders/abc/invoice-request").with(customer(tenantA))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy đơn hàng"));
        verifyNoInteractions(invoices);
    }

    @Test
    void thanSaiLa400ValidationFailed() throws Exception {
        for (String bad : List.of("{}", "{\"buyer\":null}", "{\"buyer\":{\"name\":\"" + "x".repeat(256) + "\"}}",
                "{\"buyer\":{\"taxCode\":\"" + "1".repeat(15) + "\"}}", "{\"buyer\":{\"name\":\"a\\u0007b\"}}", "")) {
            mvc.perform(put(url()).with(customer(tenantA)).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(invoices);
    }

    @Test
    void loiCuaSalesQuaDanhSachChoPhepVoiThongDiepCuaCustomerApi() throws Exception {
        when(invoices.saveInvoiceRequest(any(), any(), any()))
                .thenThrow(downstream(HttpStatus.NOT_FOUND, "{\"code\":\"ORDER_NOT_FOUND\",\"message\":\"đơn tenant=zzz\"}"))
                .thenThrow(downstream(HttpStatus.CONFLICT, "{\"code\":\"INVOICE_ALREADY_EXISTS\",\"message\":\"nội bộ ABC\"}"))
                .thenThrow(downstream(HttpStatus.BAD_REQUEST, "{\"code\":\"INVALID_TAX_CODE\",\"message\":\"nội bộ ABC\"}"))
                .thenThrow(downstream(HttpStatus.CONFLICT, "{\"code\":\"ORDER_NOT_INVOICEABLE\",\"message\":\"nội bộ ABC\"}"))
                .thenThrow(downstream(HttpStatus.FORBIDDEN, "{\"code\":\"FORBIDDEN\",\"message\":\"Thiếu khóa ShopInvoiceController/saveRequest\"}"))
                .thenThrow(downstream(HttpStatus.SERVICE_UNAVAILABLE, "{\"code\":\"X\",\"message\":\"db down\"}"));
        String[] expectCodes = {"ORDER_NOT_FOUND", "INVOICE_ALREADY_EXISTS", "INVALID_TAX_CODE", "ORDER_NOT_INVOICEABLE",
                "FORBIDDEN", "SERVICE_UNAVAILABLE"};
        int[] statuses = {404, 409, 400, 409, 403, 503};
        for (int i = 0; i < expectCodes.length; i++) {
            String json = mvc.perform(put(url()).with(customer(tenantA)).contentType(MediaType.APPLICATION_JSON)
                            .content(BODY)).andExpect(status().is(statuses[i]))
                    .andExpect(jsonPath("$.code").value(expectCodes[i])).andReturn().getResponse().getContentAsString();
            assertThat(json).doesNotContain("nội bộ ABC").doesNotContain("tenant=zzz").doesNotContain("ShopInvoiceController")
                    .doesNotContain("db down");
        }
    }

    @Test
    void phuongThucKhacVaDuongDanConKhacKhongMoDuoc() throws Exception {
        for (var req : List.of(get(url()), post(url()).contentType(MediaType.APPLICATION_JSON).content(BODY),
                put(url() + "/x").contentType(MediaType.APPLICATION_JSON).content(BODY))) {
            int s = mvc.perform(req.with(customer(tenantA))).andReturn().getResponse().getStatus();
            assertThat(s).isIn(401, 403, 404, 405);
        }
        verifyNoInteractions(invoices);
    }
}
