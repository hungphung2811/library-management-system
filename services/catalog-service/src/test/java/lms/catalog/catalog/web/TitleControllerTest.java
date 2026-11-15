package lms.catalog.catalog.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.CreateTitleCommand;
import lms.catalog.catalog.application.CreateTitleUseCase;
import lms.catalog.catalog.application.GetTitleUseCase;
import lms.catalog.catalog.application.SearchTitlesUseCase;
import lms.catalog.catalog.application.TitleResponse;
import lms.catalog.catalog.domain.DuplicateIsbnException;
import lms.catalog.catalog.domain.InvalidTitleException;
import lms.catalog.catalog.domain.TitleNotFoundException;
import lms.catalog.catalog.infrastructure.web.TitleController;
import lms.catalog.config.SecurityConfiguration;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** WebMvc cho TitleController: xác thực, phân quyền theo khóa Controller/method, validation, ánh xạ lỗi domain. */
@WebMvcTest(TitleController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class TitleControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String VALID =
            "{\"name\":\"Clean Code\",\"author\":\"Robert Martin\",\"isbn\":\"9780132350884\",\"coverPrice\":250000}";

    @Autowired MockMvc mvc;

    @MockitoBean CreateTitleUseCase createTitle;
    @MockitoBean GetTitleUseCase getTitle;
    @MockitoBean SearchTitlesUseCase searchTitles;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor withKey(String key) {
        return jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("permissions", List.of(key)))
                .authorities(new SimpleGrantedAuthority(key));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("admin", true));
    }

    private static RequestPostProcessor noPermission() {
        return jwt().jwt(j -> j.claim("tenantId", TENANT.toString()));
    }

    private static TitleResponse title(UUID id) {
        return new TitleResponse(id, "Clean Code", "Robert Martin", "9780132350884", new BigDecimal("250000"), 3);
    }

    // --- xác thực / phân quyền ---

    @Test
    void khongTokenBi401ChoCaBaEndpoint() throws Exception {
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/books")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/books/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        verifyNoInteractions(createTitle, getTitle, searchTitles);
    }

    @Test
    void khoaChuaGanBi403ChoNonAdmin() throws Exception {
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(VALID).with(noPermission()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/books").with(noPermission())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/books/" + UUID.randomUUID()).with(noPermission()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(createTitle, getTitle, searchTitles);
    }

    @Test
    void khoaCuaEndpointKhacKhongDuQuyen() throws Exception {
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(VALID)
                        .with(withKey("TitleController/search")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenKhongCoTenantBi403KeCaAdmin() throws Exception {
        mvc.perform(get("/internal/books").with(jwt().jwt(j -> j.claim("admin", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    // --- create ---

    @Test
    void taoDauSachVoiKhoaCreateTra201KemLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(createTitle.execute(any())).thenReturn(title(id));

        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(VALID)
                        .with(withKey("TitleController/create")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/internal/books/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.availableCopies").value(3));
        verify(createTitle).execute(new CreateTitleCommand("Clean Code", "Robert Martin", "9780132350884",
                new BigDecimal("250000")));
    }

    @Test
    void adminTaoDauSachKhongCanKhoa() throws Exception {
        when(createTitle.execute(any())).thenReturn(title(UUID.randomUUID()));
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(VALID).with(admin()))
                .andExpect(status().isCreated());
    }

    @Test
    void isbnCoTheBoTrong() throws Exception {
        when(createTitle.execute(any())).thenReturn(title(UUID.randomUUID()));
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"author\":\"B\",\"coverPrice\":0}").with(admin()))
                .andExpect(status().isCreated());
    }

    @Test
    void validationSaiTra400() throws Exception {
        String[] bodies = {
                "{\"author\":\"B\",\"coverPrice\":1}",
                "{\"name\":\"  \",\"author\":\"B\",\"coverPrice\":1}",
                "{\"name\":\"A\",\"coverPrice\":1}",
                "{\"name\":\"A\",\"author\":\"B\"}",
                "{\"name\":\"A\",\"author\":\"B\",\"coverPrice\":-1}",
                "{\"name\":\"A\",\"author\":\"B\",\"isbn\":\"123456789012345678901\",\"coverPrice\":1}",
                "{\"name\":\"" + "x".repeat(256) + "\",\"author\":\"B\",\"coverPrice\":1}"
        };
        for (String body : bodies) {
            mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(body).with(admin()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(createTitle);
    }

    @Test
    void trungIsbnTra409() throws Exception {
        when(createTitle.execute(any())).thenThrow(new DuplicateIsbnException("9780132350884"));
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(VALID).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ISBN"));
    }

    @Test
    void dauSachKhongHopLeTheoDomainTra422() throws Exception {
        when(createTitle.execute(any())).thenThrow(new InvalidTitleException("sai"));
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(VALID).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    // --- search ---

    @Test
    void timKiemMacDinhKhongLocConSach() throws Exception {
        when(searchTitles.execute(null, false)).thenReturn(List.of(title(UUID.randomUUID())));
        mvc.perform(get("/internal/books").with(withKey("TitleController/search")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void timKiemTruyenKeywordVaAvailable() throws Exception {
        when(searchTitles.execute("clean", true)).thenReturn(List.of());
        mvc.perform(get("/internal/books").param("keyword", "clean").param("available", "true").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verify(searchTitles).execute(eq("clean"), eq(true));
    }

    @Test
    void timKiemAvailableKhongPhaiBooleanTra400() throws Exception {
        mvc.perform(get("/internal/books").param("available", "abc").with(admin()))
                .andExpect(status().isBadRequest());
    }

    // --- get ---

    @Test
    void layDauSachTheoIdVoiKhoaGet() throws Exception {
        UUID id = UUID.randomUUID();
        when(getTitle.execute(id)).thenReturn(title(id));
        mvc.perform(get("/internal/books/" + id).with(withKey("TitleController/get")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Clean Code"));
    }

    @Test
    void khongThayDauSachTra404() throws Exception {
        UUID id = UUID.randomUUID();
        when(getTitle.execute(id)).thenThrow(new TitleNotFoundException(id));
        mvc.perform(get("/internal/books/" + id).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TITLE_NOT_FOUND"));
    }

    @Test
    void idKhongPhaiUuidTra400() throws Exception {
        mvc.perform(get("/internal/books/khong-phai-uuid").with(admin())).andExpect(status().isBadRequest());
    }
}
