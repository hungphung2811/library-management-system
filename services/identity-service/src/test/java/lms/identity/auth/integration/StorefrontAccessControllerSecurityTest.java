package lms.identity.auth.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.IssueStorefrontTokenUseCase;
import lms.identity.auth.application.IssueStorefrontTokenUseCase.StorefrontAccess;
import lms.identity.auth.infrastructure.web.StorefrontAccessController;
import lms.identity.config.SecurityConfiguration;
import lms.identity.tenant.application.StorefrontTenant;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** POST /internal/storefront/access-tokens: công khai ở lớp xác thực, bảo vệ bằng X-Storefront-Client-Secret. */
@WebMvcTest(StorefrontAccessController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.identity.storefront.client-secret=shop-secret-123", "app.internal-api-key=internal-key-999"})
class StorefrontAccessControllerSecurityTest {

    static final String PATH = "/internal/storefront/access-tokens";
    static final String BODY = "{\"tenantCode\":\"shop-a\"}";
    static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mvc;

    @MockitoBean IssueStorefrontTokenUseCase issue;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void dungBiMatKhongCanJwtTra200VoiThanDungHopDong() throws Exception {
        when(issue.execute("shop-a")).thenReturn(Optional.of(new StorefrontAccess(
                new StorefrontTenant(TENANT_ID, "shop-a", "Hiệu sách A", List.of("LENDING", "SALES")), "jwt.token.value", 300)));

        mvc.perform(post(PATH).header("X-Storefront-Client-Secret", "shop-secret-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.code").value("shop-a"))
                .andExpect(jsonPath("$.name").value("Hiệu sách A"))
                .andExpect(jsonPath("$.accessToken").value("jwt.token.value"))
                .andExpect(jsonPath("$.expiresIn").value(300))
                .andExpect(jsonPath("$.modules[0]").value("LENDING"))
                .andExpect(jsonPath("$.modules[1]").value("SALES"));
    }

    @Test
    void thieuSaiHoacRongBiMatTra401VaKhongPhatToken() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post(PATH).header("X-Storefront-Client-Secret", "sai")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("X-Storefront-Client-Secret", "SHOP-SECRET-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("X-Storefront-Client-Secret", "")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(issue, never()).execute(any());
    }

    @Test
    void khongDungInternalApiKeyThayBiMat() throws Exception {
        mvc.perform(post(PATH).header("X-Internal-Api-Key", "internal-key-999")
                        .header("X-Storefront-Client-Secret", "internal-key-999")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("X-Internal-Api-Key", "internal-key-999")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(issue, never()).execute(any());
    }

    @Test
    void jwtHopLeKhongThayChoBiMat() throws Exception {
        mvc.perform(post(PATH).with(jwt().jwt(j -> j.claim("admin", true)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(issue, never()).execute(any());
    }

    @Test
    void moiTruongHopKhongKhaDungCungTra404VoiCungThan() throws Exception {
        when(issue.execute(any())).thenReturn(Optional.empty());
        String expected = null;
        for (String code : new String[]{"khong-co", "bi-ngung", "chi-lending", "!!sai dinh dang!!", ""}) {
            var result = mvc.perform(post(PATH).header("X-Storefront-Client-Secret", "shop-secret-123")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"tenantCode\":\"" + code + "\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"))
                    .andReturn().getResponse().getContentAsString();
            if (expected == null) expected = result;
            org.junit.jupiter.api.Assertions.assertEquals(stripTimestamp(expected), stripTimestamp(result));
        }
        // thiếu tenantCode cũng là 404 chung, không phải 400
        mvc.perform(post(PATH).header("X-Storefront-Client-Secret", "shop-secret-123")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
    }

    @Test
    void bodyHongTra400() throws Exception {
        mvc.perform(post(PATH).header("X-Storefront-Client-Secret", "shop-secret-123")
                        .contentType(MediaType.APPLICATION_JSON).content("{khong-phai-json"))
                .andExpect(status().isBadRequest());
        verify(issue, never()).execute(any());
    }

    @Test
    void chiMotDuongDanCongKhaiCacDuongDanKhacVanCanToken() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/storefront/other").header("X-Storefront-Client-Secret", "shop-secret-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    private static String stripTimestamp(String json) {
        return json.replaceAll("\"timestamp\"\\s*:\\s*\"[^\"]*\"", "");
    }
}
