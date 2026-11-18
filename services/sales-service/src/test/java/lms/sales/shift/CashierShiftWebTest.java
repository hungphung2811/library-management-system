package lms.sales.shift;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.admin;
import static lms.sales.support.WebTokens.noPermission;
import static lms.sales.support.WebTokens.userWithKeys;
import static lms.sales.support.WebTokens.withKey;
import static lms.sales.support.WebTokens.withKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.shift.application.CashierShiftAdminUseCases;
import lms.sales.shift.application.CashierShiftUseCases;
import lms.sales.shift.application.ShiftSettingsUseCases;
import lms.sales.shift.infrastructure.web.CashierShiftAdminController;
import lms.sales.shift.infrastructure.web.CashierShiftController;
import lms.sales.shift.infrastructure.web.CashierShiftSettingsController;
import lms.sales.support.Harness;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Phân quyền động của ca thu ngân theo khóa đã chốt ở ADR-0030 Phụ lục A (kiểm khóa bật lại), quyền sở hữu ca ("của tôi"
 * không đọc/đóng ca người khác, ca người khác 404 cùng thân), module SALES, tenant. Nghiệp vụ thật trên bộ giả.
 */
@WebMvcTest({CashierShiftController.class, CashierShiftAdminController.class, CashierShiftSettingsController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        CashierShiftWebTest.Beans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class CashierShiftWebTest {

    @TestConfiguration
    static class Beans {
        @Bean
        ShiftFixture shiftFixture() {
            return new ShiftFixture(new Harness());
        }

        @Bean
        CashierShiftUseCases cashierShiftUseCases(ShiftFixture f) {
            return f.cashier;
        }

        @Bean
        CashierShiftAdminUseCases cashierShiftAdminUseCases(ShiftFixture f) {
            return f.admin;
        }

        @Bean
        ShiftSettingsUseCases shiftSettingsUseCases(ShiftFixture f) {
            return f.settings;
        }
    }

    private static final String B = "/internal/sales/shifts";
    private static final String ID = UUID.randomUUID().toString();
    private static final String OPEN_BODY = "{\"openingFloat\":100000,\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
    private static final String CLOSE_BODY = "{\"countedCash\":100000}";

    record Endpoint(HttpMethod method, String url, String body, String key) {
    }

    /** Khóa đúng như Phụ lục A: CashierShiftController (5), CashierShiftAdminController (5), CashierShiftSettingsController (2). */
    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(HttpMethod.POST, B, OPEN_BODY, "CashierShiftController/open"),
            new Endpoint(HttpMethod.GET, B + "/me/current", null, "CashierShiftController/current"),
            new Endpoint(HttpMethod.GET, B + "/me", null, "CashierShiftController/mine"),
            new Endpoint(HttpMethod.GET, B + "/me/" + ID, null, "CashierShiftController/getMine"),
            new Endpoint(HttpMethod.POST, B + "/me/current/closing", CLOSE_BODY, "CashierShiftController/close"),
            new Endpoint(HttpMethod.GET, B, null, "CashierShiftAdminController/search"),
            new Endpoint(HttpMethod.GET, B + "/" + ID, null, "CashierShiftAdminController/get"),
            new Endpoint(HttpMethod.POST, B + "/" + ID + "/closing", CLOSE_BODY,
                    "CashierShiftAdminController/closeOnBehalf"),
            new Endpoint(HttpMethod.POST, B + "/" + ID + "/reopening", "{\"reason\":\"dem nham\"}",
                    "CashierShiftAdminController/reopen"),
            new Endpoint(HttpMethod.POST, B + "/" + ID + "/variance-approval", "{\"note\":\"ok\"}",
                    "CashierShiftAdminController/approveVariance"),
            new Endpoint(HttpMethod.GET, "/internal/sales/shift-settings", null, "CashierShiftSettingsController/get"),
            new Endpoint(HttpMethod.PUT, "/internal/sales/shift-settings",
                    "{\"requireOpenShiftForPos\":false,\"varianceThreshold\":0,\"version\":0}",
                    "CashierShiftSettingsController/update"));

    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerMapping handlerMapping;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

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
    void khoaThuNganKhongVaoDuocEndpointQuanLyVaNguocLai() throws Exception {
        List<String> cashierKeys = ENDPOINTS.stream().map(Endpoint::key)
                .filter(k -> k.startsWith("CashierShiftController/")).toList();
        for (Endpoint e : ENDPOINTS) {
            boolean adminEndpoint = e.key().startsWith("CashierShiftAdminController/")
                    || e.key().startsWith("CashierShiftSettingsController/");
            if (adminEndpoint) {
                assertThat(call(e, withKeys(TENANT, List.of("SALES"), cashierKeys.toArray(new String[0]))))
                        .as("thu ngân có đủ khóa của mình vẫn không vào được " + e.key()).isEqualTo(403);
            }
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
            for (RequestPostProcessor token : List.of(withKeys(TENANT, List.of("LENDING"), e.key()),
                    admin(List.of("LENDING")))) {
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

    @Test
    void thuNganKhongXemHayDongCaCuaNguoiKhacQuaEndpointCuaToi() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        String[] keys = {"CashierShiftController/open", "CashierShiftController/current",
                "CashierShiftController/getMine", "CashierShiftController/close", "CashierShiftController/mine"};
        String body = "{\"openingFloat\":0,\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        String opened = mvc.perform(request(HttpMethod.POST, B).with(userWithKeys(owner, keys))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.phase").value("OPEN")).andReturn().getResponse().getContentAsString();
        String shiftId = com.jayway.jsonpath.JsonPath.read(opened, "$.id");

        // người khác: xem chi tiết, đóng, current đều 404 SHIFT_NOT_FOUND
        mvc.perform(request(HttpMethod.GET, B + "/me/" + shiftId).with(userWithKeys(intruder, keys)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHIFT_NOT_FOUND"));
        String missing = mvc.perform(request(HttpMethod.GET, B + "/me/" + UUID.randomUUID())
                        .with(userWithKeys(intruder, keys))).andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString();
        String foreign = mvc.perform(request(HttpMethod.GET, B + "/me/" + shiftId).with(userWithKeys(intruder, keys)))
                .andReturn().getResponse().getContentAsString();
        assertThat(foreign.replaceAll("\"timestamp\":\"[^\"]*\"", ""))
                .as("ca của người khác cùng thân với ca không tồn tại")
                .isEqualTo(missing.replaceAll("\"timestamp\":\"[^\"]*\"", ""));
        mvc.perform(request(HttpMethod.GET, B + "/me/current").with(userWithKeys(intruder, keys)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHIFT_NOT_FOUND"));
        mvc.perform(request(HttpMethod.POST, B + "/me/current/closing").with(userWithKeys(intruder, keys))
                        .contentType(MediaType.APPLICATION_JSON).content(CLOSE_BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHIFT_NOT_FOUND"));
        mvc.perform(request(HttpMethod.GET, B + "/me").with(userWithKeys(intruder, keys)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));

        // chủ ca xem được, ca của họ vẫn mở (người khác không đóng được)
        mvc.perform(request(HttpMethod.GET, B + "/me/" + shiftId).with(userWithKeys(owner, keys)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shift.id").value(shiftId))
                .andExpect(jsonPath("$.provisionalExpectedCash").value(0))
                .andExpect(jsonPath("$.report.methods.length()").value(6));
        mvc.perform(request(HttpMethod.GET, B + "/me/current").with(userWithKeys(owner, keys)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shift.status").value("OPEN"));
    }

    @Test
    void loiNghiepVuTraDungMaVaTrangThaiHttp() throws Exception {
        UUID cashier = UUID.randomUUID();
        String[] keys = {"CashierShiftController/open", "CashierShiftController/close"};
        String body = "{\"openingFloat\":0,\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(request(HttpMethod.POST, B).with(userWithKeys(cashier, keys))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        // mở lần hai: 409 SHIFT_ALREADY_OPEN
        mvc.perform(request(HttpMethod.POST, B).with(userWithKeys(cashier, keys))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingFloat\":0,\"clientRequestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_ALREADY_OPEN"));
        // tiền đầu ca âm: 400 do kiểm đầu vào
        mvc.perform(request(HttpMethod.POST, B).with(userWithKeys(UUID.randomUUID(), keys))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingFloat\":-5,\"clientRequestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
        // đóng lệch không lý do: 400 SHIFT_VARIANCE_REASON_REQUIRED
        mvc.perform(request(HttpMethod.POST, B + "/me/current/closing").with(userWithKeys(cashier, keys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIFT_VARIANCE_REASON_REQUIRED"));
        // đóng đúng tiền: 200
        mvc.perform(request(HttpMethod.POST, B + "/me/current/closing").with(userWithKeys(cashier, keys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.phase").value("CLOSED"));
        // quản lý thao tác ca không tồn tại: 404
        mvc.perform(request(HttpMethod.POST, B + "/" + UUID.randomUUID() + "/reopening").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHIFT_NOT_FOUND"));
    }

    @Test
    void capNhatCauHinhVaDocLai() throws Exception {
        // Các test khác dùng chung bộ giả nên phiên bản hiện tại không chắc là 0: đọc rồi cập nhật theo phiên bản đó
        String current = mvc.perform(request(HttpMethod.GET, "/internal/sales/shift-settings")
                        .with(withKey("CashierShiftSettingsController/get")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.varianceThreshold").value(0))
                .andReturn().getResponse().getContentAsString();
        int version = com.jayway.jsonpath.JsonPath.read(current, "$.version");
        mvc.perform(request(HttpMethod.PUT, "/internal/sales/shift-settings")
                        .with(withKey("CashierShiftSettingsController/update"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireOpenShiftForPos\":true,\"varianceThreshold\":5000,\"version\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requireOpenShiftForPos").value(true))
                .andExpect(jsonPath("$.varianceThreshold").value(5000))
                .andExpect(jsonPath("$.version").value(version + 1));
        mvc.perform(request(HttpMethod.PUT, "/internal/sales/shift-settings")
                        .with(withKey("CashierShiftSettingsController/update"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireOpenShiftForPos\":true,\"varianceThreshold\":-1,\"version\":" + (version + 1) + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.PUT, "/internal/sales/shift-settings")
                        .with(withKey("CashierShiftSettingsController/update"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireOpenShiftForPos\":false,\"varianceThreshold\":0,\"version\":" + version + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTINGS_VERSION_CONFLICT"));
    }

    /** Tập khóa thật của ba controller phải đúng tập đã chốt ở ADR-0030 Phụ lục A (đổi tên là đổi khóa). */
    @Test
    void tapKhoaThatKhopPhuLucA() {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        Set<String> actual = new TreeSet<>();
        for (HandlerMethod h : handlerMapping.getHandlerMethods().values()) {
            if (h.getBeanType().getName().startsWith("lms.sales.shift.")) {
                PermissionKeyResolver.Resolved r = resolver.resolve(h);
                assertThat(r.mode()).as(h.toString()).isEqualTo(PermissionKeyResolver.Mode.PERMISSION);
                assertThat(r.source()).as(h.toString()).isEqualTo(PermissionKeyResolver.SOURCE_AUTO);
                assertThat(r.requiredModule()).as(h.toString()).isEqualTo(lms.security.permission.TenantModule.SALES);
                actual.add(r.key());
            }
        }
        assertThat(actual).containsExactlyInAnyOrder(
                "CashierShiftController/open", "CashierShiftController/current", "CashierShiftController/mine",
                "CashierShiftController/getMine", "CashierShiftController/close",
                "CashierShiftAdminController/search", "CashierShiftAdminController/get",
                "CashierShiftAdminController/closeOnBehalf", "CashierShiftAdminController/reopen",
                "CashierShiftAdminController/approveVariance",
                "CashierShiftSettingsController/get", "CashierShiftSettingsController/update");
    }
}
