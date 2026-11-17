package lms.identity.tenant.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.tenant.application.AddStorefrontOriginUseCase;
import lms.identity.tenant.application.ListMyStorefrontOriginsUseCase;
import lms.identity.tenant.application.ListStorefrontOriginsUseCase;
import lms.identity.tenant.application.MyStorefrontOriginsResponse;
import lms.identity.tenant.application.RemoveStorefrontOriginUseCase;
import lms.identity.tenant.application.SetStorefrontOriginEnabledUseCase;
import lms.identity.tenant.application.StorefrontOriginListResponse;
import lms.identity.tenant.application.StorefrontOriginResponse;
import lms.identity.tenant.domain.InvalidStorefrontOriginException;
import lms.identity.tenant.domain.InvalidStorefrontOriginException.Reason;
import lms.identity.tenant.domain.StorefrontOriginDuplicatedException;
import lms.identity.tenant.domain.StorefrontOriginLimitExceededException;
import lms.identity.tenant.domain.StorefrontOriginNotFoundException;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.infrastructure.web.PlatformStorefrontOriginController;
import lms.identity.tenant.infrastructure.web.TenantExceptionHandler;
import lms.identity.tenant.infrastructure.web.TenantStorefrontOriginController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * ADR-0013: API platform origin storefront chỉ cho platformAdmin; API tenant chỉ đọc, khóa động
 * {@code TenantStorefrontOriginController/list}, DTO không lộ người tạo; ánh xạ mã lỗi. Bật lại kiểm khóa bằng
 * @TestPropertySource, mock PermissionKeyRegistrar. Không cần Docker.
 */
@WebMvcTest({PlatformStorefrontOriginController.class, TenantStorefrontOriginController.class})
@Import({SecurityConfiguration.class, TenantExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class StorefrontOriginSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean ListStorefrontOriginsUseCase list;
    @MockitoBean AddStorefrontOriginUseCase add;
    @MockitoBean SetStorefrontOriginEnabledUseCase setEnabled;
    @MockitoBean RemoveStorefrontOriginUseCase remove;
    @MockitoBean ListMyStorefrontOriginsUseCase listMine;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final UUID OPERATOR = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ORIGIN = UUID.randomUUID();
    private static final String BASE = "/api/platform/tenants/" + TENANT + "/storefront-origins";
    private static final StorefrontOriginResponse RESPONSE = new StorefrontOriginResponse(ORIGIN,
            "https://shop.a.vn", true, Instant.parse("2026-11-17T01:00:00Z"), OPERATOR,
            Instant.parse("2026-11-17T01:00:00Z"), OPERATOR);

    private RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(OPERATOR.toString()).claim("platformAdmin", true));
    }

    private RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true)
                .claim("tenantId", TENANT.toString()).claim("modules", List.of("LENDING", "SALES")));
    }

    private List<MockHttpServletRequestBuilder> platformEndpoints() {
        MediaType json = MediaType.APPLICATION_JSON;
        return List.of(get(BASE), post(BASE).contentType(json).content("{\"origin\":\"https://shop.a.vn\"}"),
                put(BASE + "/" + ORIGIN).contentType(json).content("{\"enabled\":false}"), delete(BASE + "/" + ORIGIN));
    }

    @Test
    void platformKhongTokenBi401() throws Exception {
        for (MockHttpServletRequestBuilder request : platformEndpoints()) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void quanTriVienTenantVaNguoiThuongGoiApiPlatformBi403VaKhongGoiUseCase() throws Exception {
        // kể cả admin=true và có sẵn khóa Controller/method: @PlatformAdminOnly không dùng khóa
        RequestPostProcessor withKeys = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true)
                .claim("tenantId", TENANT.toString())
                .claim("permissions", List.of("PlatformStorefrontOriginController/list",
                        "PlatformStorefrontOriginController/add")));
        for (MockHttpServletRequestBuilder request : platformEndpoints()) {
            mvc.perform(request.with(tenantAdmin())).andExpect(status().isForbidden());
        }
        for (MockHttpServletRequestBuilder request : platformEndpoints()) {
            mvc.perform(request.with(withKeys)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(list, add, setEnabled, remove);
    }

    @Test
    void platformAdminKhongCanTenantIdQuanLyBonThaoTac() throws Exception {
        when(list.execute(TENANT)).thenReturn(new StorefrontOriginListResponse(10, List.of(RESPONSE)));
        when(add.execute(eq(TENANT), eq("https://shop.a.vn"), eq(OPERATOR))).thenReturn(RESPONSE);
        when(setEnabled.execute(TENANT, ORIGIN, false, OPERATOR)).thenReturn(new StorefrontOriginResponse(ORIGIN,
                "https://shop.a.vn", false, RESPONSE.createdAt(), OPERATOR, RESPONSE.updatedAt(), OPERATOR));

        mvc.perform(get(BASE).with(platformAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.maxOrigins").value(10))
                .andExpect(jsonPath("$.origins[0].origin").value("https://shop.a.vn"))
                .andExpect(jsonPath("$.origins[0].createdBy").value(OPERATOR.toString()));
        mvc.perform(post(BASE).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":\"https://shop.a.vn\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ORIGIN.toString()))
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(put(BASE + "/" + ORIGIN).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(delete(BASE + "/" + ORIGIN).with(platformAdmin())).andExpect(status().isNoContent());
        verify(remove).execute(TENANT, ORIGIN, OPERATOR);
    }

    @Test
    void bodyThieuHoacRongLa400KhongGoiUseCase() throws Exception {
        mvc.perform(post(BASE).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"origin\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(BASE + "/" + ORIGIN).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(add, setEnabled);
    }

    @Test
    void anhXaMaLoi() throws Exception {
        UUID owner = UUID.randomUUID();
        when(add.execute(eq(TENANT), eq("https://bad.vn/x"), any()))
                .thenThrow(new InvalidStorefrontOriginException(Reason.PATH));
        when(add.execute(eq(TENANT), eq("https://dup.vn"), any()))
                .thenThrow(new StorefrontOriginDuplicatedException("đã gán cho tenant khác", owner));
        when(add.execute(eq(TENANT), eq("https://full.vn"), any()))
                .thenThrow(new StorefrontOriginLimitExceededException(10));
        when(list.execute(TENANT)).thenThrow(new TenantNotFoundException("Không tìm thấy tenant"));
        when(setEnabled.execute(eq(TENANT), eq(ORIGIN), eq(true), any()))
                .thenThrow(new StorefrontOriginNotFoundException("Không tìm thấy origin"));

        String json = "{\"origin\":\"%s\"}";
        mvc.perform(post(BASE).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.formatted("https://bad.vn/x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ORIGIN_INVALID"))
                .andExpect(jsonPath("$.details[0]").value("PATH"));
        mvc.perform(post(BASE).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.formatted("https://dup.vn")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORIGIN_DUPLICATED"))
                .andExpect(jsonPath("$.details[0]").value("tenantId=" + owner));
        mvc.perform(post(BASE).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.formatted("https://full.vn")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORIGIN_LIMIT_EXCEEDED"));
        mvc.perform(get(BASE).with(platformAdmin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
        mvc.perform(put(BASE + "/" + ORIGIN).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STOREFRONT_ORIGIN_NOT_FOUND"));
    }

    // ---- tenant admin chỉ xem ----

    @Test
    void tenantAdminXemDuocVaJsonChiCoOriginVaEnabled() throws Exception {
        when(listMine.execute()).thenReturn(new MyStorefrontOriginsResponse(
                List.of(new MyStorefrontOriginsResponse.Item("https://shop.a.vn", true),
                        new MyStorefrontOriginsResponse.Item("https://old.a.vn", false))));

        String json = mvc.perform(get("/internal/storefront-origins").with(tenantAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.origins[0].origin").value("https://shop.a.vn"))
                .andExpect(jsonPath("$.origins[1].enabled").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("createdBy", "updatedBy", "createdAt", "id\"", "maxOrigins");
        verify(listMine).execute();
    }

    @Test
    void nguoiThuongThieuKhoaBi403VaKhongCoTenantIdBi403TenantRequired() throws Exception {
        RequestPostProcessor reader = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("tenantId", TENANT.toString()).claim("permissions", List.of("OtherController/other")));
        mvc.perform(get("/internal/storefront-origins").with(reader)).andExpect(status().isForbidden());

        RequestPostProcessor noTenant = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true));
        mvc.perform(get("/internal/storefront-origins").with(noTenant)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        mvc.perform(get("/internal/storefront-origins")).andExpect(status().isUnauthorized());
        verifyNoInteractions(listMine);
    }

    @Test
    void khoaDongDuocGanChoRoleChoXemVaKhoaKhacKhongDuQua() throws Exception {
        when(listMine.execute()).thenReturn(new MyStorefrontOriginsResponse(List.of()));
        RequestPostProcessor viewer = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("tenantId", TENANT.toString())
                .claim("permissions", List.of("TenantStorefrontOriginController/list")))
                .authorities(new SimpleGrantedAuthority("TenantStorefrontOriginController/list"));
        mvc.perform(get("/internal/storefront-origins").with(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.origins").isEmpty());
    }

    @Test
    void apiTenantKhongCoMappingGhi() throws Exception {
        mvc.perform(post("/internal/storefront-origins").with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":\"https://x.vn\"}")).andExpect(status().is4xxClientError());
        mvc.perform(put("/internal/storefront-origins/" + ORIGIN).with(tenantAdmin())).andExpect(status().is4xxClientError());
        mvc.perform(delete("/internal/storefront-origins/" + ORIGIN).with(tenantAdmin())).andExpect(status().is4xxClientError());
        verifyNoInteractions(add, setEnabled, remove);
    }
}
