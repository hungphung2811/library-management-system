package lms.sales.contact;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.admin;
import static lms.sales.support.WebTokens.storefrontService;
import static lms.sales.support.WebTokens.userWithKeys;
import static lms.sales.support.WebTokens.withKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.contact.application.ShopContactQueryUseCases;
import lms.sales.contact.application.ShopContactWorkflowUseCases;
import lms.sales.contact.application.SubmitShopContactUseCase;
import lms.sales.contact.domain.ContactStatus;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.contact.infrastructure.web.SalesContactController;
import lms.sales.contact.infrastructure.web.ShopContactController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T2 (ADR-0015): ma trận khóa x endpoint x module với kiểm khóa bật lại, tập tên trường JSON của 3 view, mass
 * assignment (AC5), người gửi lấy từ JWT, PII không lộ trong thân lỗi. Nghiệp vụ thật chạy trên bộ giả trong bộ nhớ.
 */
@WebMvcTest({ShopContactController.class, SalesContactController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        ShopContactWebTest.Beans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class ShopContactWebTest {

    @TestConfiguration
    static class Beans {
        @Bean
        ContactFixture contactFixture() {
            return new ContactFixture();
        }

        @Bean
        SubmitShopContactUseCase submit(ContactFixture f) {
            return f.submit;
        }

        @Bean
        ShopContactQueryUseCases queries(ContactFixture f) {
            return f.queries;
        }

        @Bean
        ShopContactWorkflowUseCases workflow(ContactFixture f) {
            return f.workflow;
        }
    }

    private static final String CREATE = "ShopContactController/create";
    private static final String SEARCH = "SalesContactController/search";
    private static final String GET = "SalesContactController/get";
    private static final String GET_CONTACT = "SalesContactController/getContact";
    private static final String UPDATE_STATUS = "SalesContactController/updateStatus";
    private static final String ANONYMIZE = "SalesContactController/anonymize";
    private static final String C = "/internal/sales/shop/contact-requests";
    private static final String S = "/internal/sales/contact-requests";

    @Autowired MockMvc mvc;
    @Autowired ContactFixture f;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static String body(String message) {
        return "{\"topic\":\"ORDER\",\"fullName\":\"Nguyễn Văn Bình\",\"email\":\"binh@example.vn\","
                + "\"phone\":\"0912345678\",\"message\":\"" + message + "\",\"consent\":true}";
    }

    private ShopContactRequest seed(UUID user, String message) {
        f.send(TENANT, user, message);
        return f.rows.values().stream().filter(r -> user.equals(r.customerUserId())).findFirst().orElseThrow();
    }

    private static RequestPostProcessor staff(String... keys) {
        return withKeys(TENANT, List.of("SALES"), keys);
    }

    // ---- create ----

    @Test
    void khachCoKhoaGui201KhongThanNoStoreVaNguoiGuiLaSubCuaToken() throws Exception {
        UUID customer = UUID.randomUUID();
        mvc.perform(post(C).with(userWithKeys(customer, CREATE)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Don cua toi chua giao")))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        ShopContactRequest r = f.rows.values().stream().filter(x -> customer.equals(x.customerUserId()))
                .findFirst().orElseThrow();
        assertThat(r.tenantId()).isEqualTo(TENANT);
        assertThat(r.status()).isEqualTo(ContactStatus.NEW);
    }

    @Test
    void massAssignmentKhongTheGhiDeTrangThaiChuHayTenant() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        String evil = body("mass assignment").replace("{", "{\"status\":\"CLOSED\",\"customerUserId\":\"" + victim
                + "\",\"tenantId\":\"" + UUID.randomUUID() + "\",\"id\":\"" + UUID.randomUUID()
                + "\",\"createdAt\":\"2000-01-01T00:00:00Z\",\"orderId\":\"" + UUID.randomUUID()
                + "\",\"version\":99,\"piiAnonymizedAt\":\"2000-01-01T00:00:00Z\",");
        mvc.perform(post(C).with(userWithKeys(customer, CREATE)).contentType(MediaType.APPLICATION_JSON)
                .content(evil)).andExpect(status().isCreated());
        ShopContactRequest r = f.rows.values().stream().filter(x -> customer.equals(x.customerUserId()))
                .findFirst().orElseThrow();
        assertThat(r.status()).isEqualTo(ContactStatus.NEW);
        assertThat(r.tenantId()).isEqualTo(TENANT);
        assertThat(r.version()).isZero();
        assertThat(r.closedAt()).isNull();
        assertThat(r.piiAnonymizedAt()).isNull();
        assertThat(r.createdAt()).isAfter(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(f.rows.values().stream().anyMatch(x -> victim.equals(x.customerUserId()))).isFalse();
    }

    @Test
    void guiTrung201MotDongVaSauMuoiPhutDongMoi() throws Exception {
        UUID customer = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(C).with(userWithKeys(customer, CREATE)).contentType(MediaType.APPLICATION_JSON)
                    .content(body("trung"))).andExpect(status().isCreated());
        }
        assertThat(f.rows.values().stream().filter(x -> customer.equals(x.customerUserId())).count()).isEqualTo(1);
    }

    @Test
    void yeuCauThuSauLa429VoiMaSalesVaKhongLoPii() throws Exception {
        UUID customer = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            mvc.perform(post(C).with(userWithKeys(customer, CREATE)).contentType(MediaType.APPLICATION_JSON)
                    .content(body("lan " + i))).andExpect(status().isCreated());
        }
        MvcResult r = mvc.perform(post(C).with(userWithKeys(customer, CREATE))
                        .contentType(MediaType.APPLICATION_JSON).content(body("lan 6")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SHOP_CONTACT_LIMIT_EXCEEDED")).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("binh@example.vn").doesNotContain("lan 6");
        // người khác không bị ảnh hưởng
        mvc.perform(post(C).with(userWithKeys(UUID.randomUUID(), CREATE)).contentType(MediaType.APPLICATION_JSON)
                .content(body("lan 6"))).andExpect(status().isCreated());
    }

    @Test
    void validationVaConsent() throws Exception {
        var u = userWithKeys(UUID.randomUUID(), CREATE);
        MvcResult bad = mvc.perform(post(C).with(u).contentType(MediaType.APPLICATION_JSON)
                        .content(body("x").replace("binh@example.vn", "email-sai-bi-mat")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andReturn();
        assertThat(bad.getResponse().getContentAsString()).doesNotContain("email-sai-bi-mat");
        mvc.perform(post(C).with(u).contentType(MediaType.APPLICATION_JSON)
                        .content(body("x").replace("ORDER", "LA")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(C).with(u).contentType(MediaType.APPLICATION_JSON)
                        .content(body("x").replace("\"fullName\":\"Nguyễn Văn Bình\",", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(C).with(u).contentType(MediaType.APPLICATION_JSON)
                        .content(body("x").replace("\"consent\":true", "\"consent\":false")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        mvc.perform(post(C).with(u).contentType(MediaType.APPLICATION_JSON)
                        .content(body("x").replace(",\"consent\":true", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        mvc.perform(post(C).with(u).contentType(MediaType.APPLICATION_JSON)
                        .content(body("x").replace("\"x\"", "\"a\\u0000b\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---- ma trận khóa x endpoint ----

    private List<MockHttpServletRequestBuilder> allEndpoints(UUID id) {
        List<MockHttpServletRequestBuilder> out = new ArrayList<>();
        out.add(post(C).contentType(MediaType.APPLICATION_JSON).content(body("ma tran " + id)));
        out.add(get(S));
        out.add(get(S + "/" + id));
        out.add(get(S + "/" + id + "/contact"));
        out.add(put(S + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CLOSED\",\"version\":0}"));
        out.add(post(S + "/" + id + "/anonymization"));
        return out;
    }

    private static final List<String> KEYS = List.of(CREATE, SEARCH, GET, GET_CONTACT, UPDATE_STATUS, ANONYMIZE);

    @Test
    void moiEndpointChiMoVoiDungKhoaCuaNo() throws Exception {
        UUID id = seed(UUID.randomUUID(), "ma tran seed").id();
        for (int i = 0; i < KEYS.size(); i++) {
            for (int j = 0; j < KEYS.size(); j++) {
                var request = allEndpoints(id).get(i);
                var res = mvc.perform(request.with(staff(KEYS.get(j))));
                if (i == j) {
                    int code = res.andReturn().getResponse().getStatus();
                    assertThat(code).as(KEYS.get(i)).isNotEqualTo(403).isNotEqualTo(401);
                } else {
                    res.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
                }
            }
        }
    }

    @Test
    void khongKhoaVaTokenDichVuBi403MoiEndpoint() throws Exception {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < KEYS.size(); i++) {
            mvc.perform(allEndpoints(id).get(i).with(staff()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            // token svc (customer-api) không có @StorefrontAccessible ở mọi endpoint liên hệ (AC14)
            mvc.perform(allEndpoints(id).get(i).with(storefrontService(TENANT, List.of("SALES"))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void khachGoiEndpointNhanVien403VaNhanVienKhongCoKhoaCreate() throws Exception {
        UUID id = UUID.randomUUID();
        for (int i = 1; i < KEYS.size(); i++) {
            mvc.perform(allEndpoints(id).get(i).with(userWithKeys(UUID.randomUUID(), CREATE)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void tatModuleSalesLaModuleNotEnabledKeCaAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < KEYS.size(); i++) {
            mvc.perform(allEndpoints(id).get(i).with(withKeys(TENANT, List.of("LENDING"), KEYS.get(i))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
            mvc.perform(allEndpoints(id).get(i).with(admin(List.of("LENDING"))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        }
    }

    @Test
    void adminTenantBoQuaKiemKhoa() throws Exception {
        UUID id = seed(UUID.randomUUID(), "admin seed").id();
        mvc.perform(get(S + "/" + id).with(admin())).andExpect(status().isOk());
    }

    @Test
    void khongTokenLa401() throws Exception {
        mvc.perform(post(C).contentType(MediaType.APPLICATION_JSON).content(body("x"))).andExpect(status().isUnauthorized());
        mvc.perform(get(S)).andExpect(status().isUnauthorized());
    }

    // ---- view allowlist: so tập tên trường ----

    private JsonNode read(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static java.util.Set<String> names(JsonNode n) {
        java.util.Set<String> out = new TreeSet<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void viewChiCoDungCacTruongVaCheLienHe() throws Exception {
        UUID customer = UUID.randomUUID();
        ShopContactRequest r = seed(customer, "noi dung rieng tu");
        JsonNode page = read(mvc.perform(get(S).with(staff(SEARCH))).andExpect(status().isOk()).andReturn());
        assertThat(names(page)).containsExactlyInAnyOrder("items", "total", "page", "size");
        JsonNode item = null;
        for (JsonNode n : page.get("items")) {
            if (n.get("id").asText().equals(r.id().toString())) {
                item = n;
            }
        }
        assertThat(item).isNotNull();
        assertThat(names(item)).containsExactlyInAnyOrder("id", "topic", "status", "createdAt", "closedAt",
                "contact", "piiAnonymizedAt", "version");
        assertThat(names(item.get("contact"))).containsExactlyInAnyOrder("name", "email", "phone");
        assertThat(item.get("contact").get("name").asText()).isEqualTo("N***** V** B***");
        assertThat(item.get("contact").get("email").asText()).isEqualTo("b***@example.vn");
        assertThat(item.get("contact").get("phone").asText()).isEqualTo("*******678");
        assertThat(item.toString()).doesNotContain("noi dung rieng tu").doesNotContain(customer.toString())
                .doesNotContain(TENANT.toString());

        JsonNode detail = read(mvc.perform(get(S + "/" + r.id()).with(staff(GET))).andExpect(status().isOk())
                .andReturn());
        assertThat(names(detail)).containsExactlyInAnyOrder("id", "topic", "status", "createdAt", "closedAt",
                "contact", "piiAnonymizedAt", "version", "message", "customerUserId");
        assertThat(detail.get("message").asText()).isEqualTo("noi dung rieng tu");
        assertThat(detail.get("contact").get("email").asText()).isEqualTo("b***@example.vn"); // vẫn che
        assertThat(detail.toString()).doesNotContain("binh@example.vn").doesNotContain(TENANT.toString());

        JsonNode contact = read(mvc.perform(get(S + "/" + r.id() + "/contact").with(staff(GET_CONTACT)))
                .andExpect(status().isOk()).andReturn());
        assertThat(names(contact)).containsExactlyInAnyOrder("fullName", "email", "phone", "piiAnonymizedAt");
        assertThat(contact.get("email").asText()).isEqualTo("binh@example.vn");
        assertThat(contact.get("fullName").asText()).isEqualTo("Nguyễn Văn Bình");
    }

    @Test
    void timKiemLocTheoTrangThaiVaPhanTrang() throws Exception {
        seed(UUID.randomUUID(), "loc 1");
        JsonNode closed = read(mvc.perform(get(S).param("status", "CLOSED").param("size", "5").with(staff(SEARCH)))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode n : closed.get("items")) {
            assertThat(n.get("status").asText()).isEqualTo("CLOSED");
        }
        mvc.perform(get(S).param("status", "KHONG_CO").with(staff(SEARCH))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- nhân viên: đổi trạng thái, 404, 409, ẩn danh ----

    @Test
    void doiTrangThaiVa409VaAnDanh() throws Exception {
        ShopContactRequest r = seed(UUID.randomUUID(), "workflow");
        mvc.perform(put(S + "/" + r.id() + "/status").with(staff(UPDATE_STATUS)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"version\":" + r.version() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(r.version() + 1));
        // version cũ
        mvc.perform(put(S + "/" + r.id() + "/status").with(staff(UPDATE_STATUS)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"version\":" + r.version() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_CONTACT_STATE_CONFLICT"));
        // thiếu version hoặc status
        mvc.perform(put(S + "/" + r.id() + "/status").with(staff(UPDATE_STATUS)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CLOSED\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(S + "/" + r.id() + "/status").with(staff(UPDATE_STATUS)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}")).andExpect(status().isBadRequest());
        // ẩn danh rồi gọi lại vẫn 200
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(S + "/" + r.id() + "/anonymization").with(staff(ANONYMIZE)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").doesNotExist())
                    .andExpect(jsonPath("$.piiAnonymizedAt").isNotEmpty())
                    .andExpect(jsonPath("$.customerUserId").doesNotExist())
                    .andExpect(jsonPath("$.contact.name").doesNotExist());
        }
        mvc.perform(get(S + "/" + r.id() + "/contact").with(staff(GET_CONTACT))).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.piiAnonymizedAt").isNotEmpty());
    }

    @Test
    void idKhongCoHoacTenantKhacLa404CungThan() throws Exception {
        ShopContactRequest r = seed(UUID.randomUUID(), "tenant khac");
        // dòng thuộc tenant khác trong bộ giả
        UUID otherTenant = UUID.randomUUID();
        f.send(otherTenant, UUID.randomUUID(), "cua tenant khac");
        UUID otherId = f.only(otherTenant).id();
        String missing = mvc.perform(get(S + "/" + UUID.randomUUID()).with(staff(GET)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_CONTACT_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String cross = mvc.perform(get(S + "/" + otherId).with(staff(GET)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_CONTACT_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(cross).get("message")).isEqualTo(json.readTree(missing).get("message"));
        mvc.perform(get(S + "/" + otherId + "/contact").with(staff(GET_CONTACT))).andExpect(status().isNotFound());
        mvc.perform(put(S + "/" + otherId + "/status").with(staff(UPDATE_STATUS))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\",\"version\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(S + "/" + otherId + "/anonymization").with(staff(ANONYMIZE))).andExpect(status().isNotFound());
        assertThat(f.rows.get(otherId).isAnonymized()).isFalse();
        assertThat(r).isNotNull();
    }
}
