package lms.catalog.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lms.catalog.catalog.application.GetTitleUseCase;
import lms.catalog.catalog.application.LookupTitlesByIsbnUseCase;
import lms.catalog.catalog.application.LookupTitlesUseCase;
import lms.catalog.catalog.application.TitleResponse;
import lms.catalog.catalog.infrastructure.web.TitleController;
import lms.catalog.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Token integration (aud lms-integration, SAAS_API_AUTHENTICATION_DESIGN pha I3) tại TitleController: chỉ get, lookup,
 * lookupByIsbn có {@code @IntegrationScope("books:read")}; mọi endpoint khác (kể cả @StorefrontAccessible, ghi) là 403;
 * thiếu scope 403 INSUFFICIENT_SCOPE; không tenant 403 TENANT_REQUIRED; tenant luôn lấy từ claim của token.
 */
@WebMvcTest(TitleController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class TitleControllerIntegrationScopeTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;

    @MockitoBean lms.catalog.catalog.application.CreateTitleUseCase createTitle;
    @MockitoBean GetTitleUseCase getTitle;
    @MockitoBean lms.catalog.catalog.application.SearchTitlesUseCase searchTitles;
    @MockitoBean lms.catalog.catalog.application.UpdateTitleUseCase updateTitle;
    @MockitoBean LookupTitlesUseCase lookupTitles;
    @MockitoBean lms.catalog.catalog.application.LookupTitlesByKeywordUseCase lookupByKeyword;
    @MockitoBean LookupTitlesByIsbnUseCase lookupByIsbn;
    @MockitoBean lms.catalog.catalog.application.GetTitlePreviewUseCase getPreview;
    @MockitoBean lms.catalog.catalog.application.UpdateTitlePreviewUseCase updatePreview;
    @MockitoBean lms.catalog.catalog.application.MoveEditionToWorkUseCase moveEditionToWork;
    @MockitoBean lms.catalog.inventory.application.PublishEditionWorkChangedUseCase editionEvents;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor integration(UUID tenant, String scope) {
        return jwt().jwt(j -> {
            j.audience(List.of("lms-integration")).subject("app").claim("scope", scope)
                    .claim("application_id", UUID.randomUUID().toString());
            if (tenant != null) j.claim("tenantId", tenant.toString());
        });
    }

    private static TitleResponse title(UUID id) {
        return new TitleResponse(id, "Clean Code", "Robert Martin", "9780132350884", new BigDecimal("250000"), 3, TENANT);
    }

    @Test
    void booksReadQuaDuocBaEndpointDoc() throws Exception {
        UUID id = UUID.randomUUID();
        when(getTitle.execute(id)).thenReturn(title(id));
        when(lookupTitles.execute(any())).thenReturn(List.of());
        when(lookupByIsbn.execute(any())).thenReturn(List.of());
        RequestPostProcessor token = integration(TENANT, "books:read");
        mvc.perform(get("/internal/books/" + id).with(token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(post("/internal/books/lookup").with(token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + id + "\"]}")).andExpect(status().isOk());
        mvc.perform(post("/internal/books/lookup-by-isbn").with(token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbns\":[\"9780132350884\"]}")).andExpect(status().isOk());
    }

    @Test
    void thieuHoacSaiScopeBi403InsufficientScope() throws Exception {
        UUID id = UUID.randomUUID();
        for (String scope : List.of("", "inventory:read orders:read", "books:write", "xbooks:read", "books:readx")) {
            RequestPostProcessor token = integration(TENANT, scope);
            mvc.perform(get("/internal/books/" + id).with(token)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
            mvc.perform(post("/internal/books/lookup").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ids\":[\"" + id + "\"]}")).andExpect(status().isForbidden());
            mvc.perform(post("/internal/books/lookup-by-isbn").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"isbns\":[\"1\"]}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(getTitle, lookupTitles, lookupByIsbn);
    }

    @Test
    void khongCoTenantBi403TenantRequired() throws Exception {
        mvc.perform(get("/internal/books/" + UUID.randomUUID()).with(integration(null, "books:read")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(getTitle);
    }

    @Test
    void endpointKhongGanAnnotationBi403VoiTokenIntegrationDuScope() throws Exception {
        UUID id = UUID.randomUUID();
        RequestPostProcessor all = integration(TENANT, "books:read books:write inventory:read orders:read");
        String create = "{\"name\":\"Clean Code\",\"author\":\"RM\",\"isbn\":\"9780132350884\",\"coverPrice\":250000}";
        mvc.perform(post("/internal/books").with(all).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(put("/internal/books/" + id).with(all).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/books").with(all)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/books/lookup?keyword=abc").with(all)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/books/" + id + "/preview").with(all)).andExpect(status().isForbidden());
        mvc.perform(put("/internal/books/" + id + "/preview").with(all).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(put("/internal/books/" + id + "/work").with(all).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(createTitle, updateTitle, searchTitles, lookupByKeyword, getPreview, updatePreview,
                moveEditionToWork);
    }

    @Test
    void tenantLayTuClaimCuaTokenKhongTuHeaderXTenantId() throws Exception {
        UUID id = UUID.randomUUID();
        AtomicReference<UUID> seen = new AtomicReference<>();
        when(getTitle.execute(id)).thenAnswer(invocation -> {
            seen.set(TenantContext.require());
            return title(id);
        });
        mvc.perform(get("/internal/books/" + id + "?tenantId=" + OTHER_TENANT)
                        .header("X-Tenant-Id", OTHER_TENANT.toString()).with(integration(TENANT, "books:read")))
                .andExpect(status().isOk());
        assertThat(seen.get()).isEqualTo(TENANT).isNotEqualTo(OTHER_TENANT);
    }
}
