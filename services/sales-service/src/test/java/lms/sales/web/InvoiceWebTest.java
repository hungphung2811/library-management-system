package lms.sales.web;

import static lms.sales.support.WebTokens.admin;
import static lms.sales.support.WebTokens.noPermission;
import static lms.sales.support.WebTokens.withKey;
import static lms.sales.support.WebTokens.withKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.invoicing.application.InvoiceQueryUseCases;
import lms.sales.invoicing.application.InvoiceRequestUseCases;
import lms.sales.invoicing.application.InvoiceSettingsUseCases;
import lms.sales.invoicing.application.InvoiceUseCases;
import lms.sales.invoicing.infrastructure.web.InvoiceController;
import lms.sales.invoicing.infrastructure.web.InvoiceRequestController;
import lms.sales.invoicing.infrastructure.web.InvoiceSettingsController;
import lms.sales.invoicing.infrastructure.web.ShopInvoiceController;
import lms.sales.invoicing.support.InvoicingKit;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Recipient;
import lms.sales.support.Harness;
import lms.sales.support.WebTokens;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.security.permission.PermissionKeyResolver;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Phân quyền và hợp đồng HTTP của hóa đơn (ADR-0030 gói A, Phụ lục A): khóa quyền đúng tên controller/method, thiếu khóa
 * 403, module SALES tắt 403 MODULE_NOT_ENABLED, thiếu tenant 403 TENANT_REQUIRED; IDOR của khách (đơn người khác 404 cùng
 * thân); bản in HTML có header an toàn và dữ liệu đã thoát. Use case chạy thật trên kho giả (Harness + InvoicingKit).
 */
@WebMvcTest({InvoiceController.class, InvoiceRequestController.class, InvoiceSettingsController.class,
        ShopInvoiceController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        InvoiceWebTest.Beans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class InvoiceWebTest {

    @TestConfiguration
    static class Beans {
        @Bean
        Harness harness() {
            return new Harness();
        }

        @Bean
        InvoicingKit kit(Harness h) {
            return new InvoicingKit(h);
        }

        @Bean
        InvoiceUseCases invoiceUseCases(InvoicingKit k) {
            return k.useCases;
        }

        @Bean
        InvoiceQueryUseCases invoiceQueryUseCases(InvoicingKit k) {
            return k.queries;
        }

        @Bean
        InvoiceRequestUseCases invoiceRequestUseCases(InvoicingKit k) {
            return k.requestUseCases;
        }

        @Bean
        InvoiceSettingsUseCases invoiceSettingsUseCases(InvoicingKit k) {
            return k.settingsUseCases;
        }
    }

    private static final String B = "/internal/sales";
    private static final String ID = UUID.randomUUID().toString();
    private static final String BUYER = "{\"buyer\":{\"name\":\"An\"}}";

    record Endpoint(HttpMethod method, String url, String body, String key) {
    }

    /** Đúng 11 khóa của Phụ lục A ADR-0030 cho gói A. */
    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(HttpMethod.GET, B + "/invoices", null, "InvoiceController/search"),
            new Endpoint(HttpMethod.GET, B + "/invoices/" + ID, null, "InvoiceController/get"),
            new Endpoint(HttpMethod.GET, B + "/invoices/" + ID + "/print", null, "InvoiceController/print"),
            new Endpoint(HttpMethod.POST, B + "/orders/" + ID + "/invoice", "{\"clientRequestId\":\"k1\"}",
                    "InvoiceController/issue"),
            new Endpoint(HttpMethod.POST, B + "/invoices/" + ID + "/cancellation", "{\"reason\":\"sai\"}",
                    "InvoiceController/cancel"),
            new Endpoint(HttpMethod.POST, B + "/invoices/" + ID + "/replacement",
                    "{\"reason\":\"sai\",\"clientRequestId\":\"k2\"}", "InvoiceController/replace"),
            new Endpoint(HttpMethod.GET, B + "/orders/" + ID + "/invoice-request", null, "InvoiceRequestController/get"),
            new Endpoint(HttpMethod.PUT, B + "/orders/" + ID + "/invoice-request", BUYER, "InvoiceRequestController/save"),
            new Endpoint(HttpMethod.GET, B + "/invoice-settings", null, "InvoiceSettingsController/get"),
            new Endpoint(HttpMethod.PUT, B + "/invoice-settings", "{\"sellerLegalName\":\"Nhà sách A\","
                    + "\"sellerTaxCode\":\"0123456789\",\"sellerAddress\":\"1 Lê Lợi\",\"templateCode\":\"1\","
                    + "\"series\":\"C26TAA\",\"autoIssueOnCompletion\":false,\"version\":0}",
                    "InvoiceSettingsController/update"),
            new Endpoint(HttpMethod.PUT, B + "/shop/orders/" + ID + "/invoice-request", BUYER,
                    "ShopInvoiceController/saveRequest"));

    @Autowired
    MockMvc mvc;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    Harness h;

    @Autowired
    InvoicingKit kit;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    private int call(Endpoint e, RequestPostProcessor token) throws Exception {
        var req = request(e.method(), e.url()).with(token);
        if (e.body() != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(e.body());
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    @Test
    void khongTokenBi401() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            var req = request(e.method(), e.url());
            if (e.body() != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(e.body());
            }
            mvc.perform(req).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void thieuKhoaBi403VoiMoiEndpoint() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            var req = request(e.method(), e.url()).with(noPermission());
            if (e.body() != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(e.body());
            }
            mvc.perform(req).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void dungKhoaThiQuaVaKhoaCuaEndpointKhacBi403() throws Exception {
        for (int i = 0; i < ENDPOINTS.size(); i++) {
            Endpoint e = ENDPOINTS.get(i);
            assertThat(call(e, withKey(e.key()))).as(e.key()).isNotIn(401, 403, 500);
            Endpoint other = ENDPOINTS.get((i + 1) % ENDPOINTS.size());
            assertThat(call(e, withKey(other.key()))).as(e.key() + " với khóa " + other.key()).isEqualTo(403);
        }
    }

    @Test
    void adminTenantQuaKiemKhoaKhiCoModuleSales() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            assertThat(call(e, admin())).as(e.key()).isNotIn(401, 403, 500);
        }
    }

    @Test
    void tokenKhongCoModuleSalesBi403ModuleNotEnabledKeCaAdmin() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            for (RequestPostProcessor token : List.of(withKeys(WebTokens.TENANT, List.of("LENDING"), e.key()),
                    withKeys(WebTokens.TENANT, null, e.key()), admin(List.of("LENDING")))) {
                var req = request(e.method(), e.url()).with(token);
                if (e.body() != null) {
                    req.contentType(MediaType.APPLICATION_JSON).content(e.body());
                }
                mvc.perform(req).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
            }
        }
    }

    @Test
    void tokenKhongCoTenantBi403TenantRequired() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            var req = request(e.method(), e.url()).with(withKeys(null, List.of("SALES"), e.key()));
            if (e.body() != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(e.body());
            }
            mvc.perform(req).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        }
    }

    /** Tên khóa là hợp đồng (Phụ lục A): tập khóa thật của bốn controller phải đúng 11 khóa, tất cả tự động, module SALES. */
    @Test
    void tapKhoaThatKhopPhuLucA() {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        Set<String> actual = new TreeSet<>();
        for (HandlerMethod hm : handlerMapping.getHandlerMethods().values()) {
            if (Set.of(InvoiceController.class, InvoiceRequestController.class, InvoiceSettingsController.class,
                    ShopInvoiceController.class).contains(hm.getBeanType())) {
                PermissionKeyResolver.Resolved r = resolver.resolve(hm);
                assertThat(r.mode()).as(hm.toString()).isEqualTo(PermissionKeyResolver.Mode.PERMISSION);
                assertThat(r.source()).as(hm.toString()).isEqualTo(PermissionKeyResolver.SOURCE_AUTO);
                assertThat(r.requiredModule()).isEqualTo(lms.security.permission.TenantModule.SALES);
                actual.add(r.key());
            }
        }
        assertThat(actual).containsExactlyInAnyOrder("InvoiceController/search", "InvoiceController/get",
                "InvoiceController/print", "InvoiceController/issue", "InvoiceController/cancel",
                "InvoiceController/replace", "InvoiceRequestController/get", "InvoiceRequestController/save",
                "InvoiceSettingsController/get", "InvoiceSettingsController/update",
                "ShopInvoiceController/saveRequest");
    }

    // ---- hành vi ----

    private static RequestPostProcessor user(UUID sub, String key) {
        return jwt().jwt(j -> j.subject(sub.toString()).claim("tenantId", WebTokens.TENANT.toString())
                .claim("permissions", List.of(key)).claim("modules", List.of("SALES")))
                .authorities(List.<GrantedAuthority>of(new SimpleGrantedAuthority(key)));
    }

    @Test
    void banInTraHtmlAnToanVaDuLieuDaThoat() throws Exception {
        kit.configureSeller(false);
        UUID title = h.book("<script>alert(1)</script> Sách", 100_000, null, 5);
        var order = h.tx(() -> h.placePos.create(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(title, 1, null)), PaymentMethod.CASH, null, null, null, null,
                UUID.randomUUID())));
        var invoice = kit.useCases.issue(new InvoiceUseCases.IssueCommand(h.tenant, order.id(), h.user, UUID.randomUUID()));

        mvc.perform(get(B + "/invoices/" + invoice.id() + "/print").with(withKey("InvoiceController/print")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Hóa đơn nội bộ")));
        // chi tiết JSON cho cùng hóa đơn
        mvc.perform(get(B + "/invoices/" + invoice.id()).with(withKey("InvoiceController/get")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invoiceNumber").value(org.hamcrest.Matchers.matchesPattern("\\d{8}")))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString("không thay thế")));
    }

    @Test
    void lapHoaDonTraBaMuoiMotVaLoiNghiepVuDungMa() throws Exception {
        kit.configureSeller(WebTokens.TENANT, false);
        UUID title = h.book("Sách A", 100_000, null, 5);
        var order = h.tx(() -> h.placePos.create(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(title, 1, null)), PaymentMethod.CASH, null, null, null, null,
                UUID.randomUUID())));
        String url = B + "/orders/" + order.id() + "/invoice";
        mvc.perform(request(HttpMethod.POST, url).with(withKey("InvoiceController/issue"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientRequestId\":\"k-1\"}"))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("ISSUED"));
        mvc.perform(request(HttpMethod.POST, url).with(withKey("InvoiceController/issue"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientRequestId\":\"k-2\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICE_ALREADY_EXISTS"));
        mvc.perform(request(HttpMethod.POST, url).with(withKey("InvoiceController/issue"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(B + "/invoices/" + UUID.randomUUID()).with(withKey("InvoiceController/get")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"));
        mvc.perform(request(HttpMethod.POST, B + "/invoices/" + UUID.randomUUID() + "/cancellation")
                        .with(withKey("InvoiceController/cancel")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}")).andExpect(status().isBadRequest());
        // MST sai định dạng khi lưu yêu cầu của nhân viên
        mvc.perform(put(B + "/orders/" + order.id() + "/invoice-request").with(withKey("InvoiceRequestController/save"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyer\":{\"company\":\"Cty\",\"taxCode\":\"123\",\"address\":\"đc\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TAX_CODE"));
    }

    @Test
    void timKiemNhanQueryGomMotRecord() throws Exception {
        mvc.perform(get(B + "/invoices").param("status", "ISSUED").param("kind", "ORIGINAL").param("page", "0")
                        .param("size", "5").with(withKey("InvoiceController/search")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get(B + "/invoices").param("status", "KHONG_CO").with(withKey("InvoiceController/search")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void khachChiYeuCauChoDonCuaMinhVaDonNguoiKhacLaBaTrangNotFoundCungThan() throws Exception {
        UUID title = h.book("Sách A", 100_000, null, 5);
        UUID owner = WebTokens.USER;
        UUID stranger = UUID.randomUUID();
        var placed = h.tx(() -> h.placeOnline.place(new PlaceOnlineOrderCommand(h.tenant, owner,
                List.of(new PlaceOnlineOrderCommand.Line(title, 1)), Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER,
                new Recipient("An", "0912345678", null), UUID.randomUUID())));
        String url = B + "/shop/orders/" + placed.order().id() + "/invoice-request";
        String body = "{\"buyer\":{\"name\":\"Nguyễn Văn An\",\"email\":\"an@x.vn\"}}";

        String other = mvc.perform(put(url).with(user(stranger, "ShopInvoiceController/saveRequest"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND")).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(put(B + "/shop/orders/" + UUID.randomUUID() + "/invoice-request")
                        .with(user(stranger, "ShopInvoiceController/saveRequest"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var a = mapper.readTree(other);
        var b = mapper.readTree(missing);
        assertThat(a.get("code")).as("cùng mã").isEqualTo(b.get("code"));
        assertThat(a.get("message")).as("cùng thông điệp").isEqualTo(b.get("message"));
        assertThat(a.get("details")).as("cùng chi tiết").isEqualTo(b.get("details"));
        // chủ đơn: 200, phản hồi chỉ có trạng thái, không lặp lại dữ liệu cá nhân đã gửi
        String ok = mvc.perform(put(url).with(user(owner, "ShopInvoiceController/saveRequest"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN")).andReturn().getResponse().getContentAsString();
        assertThat(ok).doesNotContain("Nguyễn Văn An").doesNotContain("an@x.vn");
        // khách không có khóa thì 403 dù là chủ đơn
        mvc.perform(put(url).with(user(owner, "ShopController/myOrder")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        // khách không vào được endpoint nhân viên bằng khóa của khách
        mvc.perform(put(B + "/orders/" + placed.order().id() + "/invoice-request")
                .with(user(owner, "ShopInvoiceController/saveRequest")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
    }

    @Test
    void capNhatCauHinhVaDocLai() throws Exception {
        UUID freshTenant = UUID.randomUUID(); // tenant riêng: kho giả dùng chung giữa các test của lớp này
        RequestPostProcessor getKey = withKeys(freshTenant, List.of("SALES"), "InvoiceSettingsController/get");
        RequestPostProcessor putKey = withKeys(freshTenant, List.of("SALES"), "InvoiceSettingsController/update");
        mvc.perform(get(B + "/invoice-settings").with(getKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(put(B + "/invoice-settings").with(putKey)
                        .contentType(MediaType.APPLICATION_JSON).content(ENDPOINTS.get(9).body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.sellerTaxCode").value("0123456789"));
        mvc.perform(put(B + "/invoice-settings").with(putKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ENDPOINTS.get(9).body().replace("0123456789", "12").replace("\"version\":0", "\"version\":1")))
                .andExpect(status().isBadRequest());
    }
}
