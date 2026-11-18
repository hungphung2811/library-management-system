package lms.catalog.catalog.web;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.CreateTitleCommand;
import lms.catalog.catalog.application.CreateTitleUseCase;
import lms.catalog.catalog.application.GetTitleUseCase;
import lms.catalog.catalog.application.SearchTitlesUseCase;
import lms.catalog.catalog.application.TitleResponse;
import lms.catalog.catalog.application.UpdateTitleCommand;
import lms.catalog.catalog.application.UpdateTitleUseCase;
import lms.catalog.catalog.domain.DuplicateIsbnException;
import lms.catalog.catalog.domain.InvalidTitleException;
import lms.catalog.catalog.domain.TitleExtras;
import lms.catalog.catalog.domain.TitleFormat;
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
    @MockitoBean UpdateTitleUseCase updateTitle;
    @MockitoBean lms.catalog.catalog.application.LookupTitlesUseCase lookupTitles;
    @MockitoBean lms.catalog.catalog.application.LookupTitlesByKeywordUseCase lookupByKeyword;
    @MockitoBean lms.catalog.catalog.application.LookupTitlesByIsbnUseCase lookupByIsbn;
    @MockitoBean lms.catalog.catalog.application.GetTitlePreviewUseCase getPreview;
    @MockitoBean lms.catalog.catalog.application.UpdateTitlePreviewUseCase updatePreview;
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
        return new TitleResponse(id, "Clean Code", "Robert Martin", "9780132350884", new BigDecimal("250000"), 3, TENANT);
    }

    private static RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.claim("platformAdmin", true));
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

    // --- update ---

    @Test
    void suaDauSachVoiKhoaUpdateTra200() throws Exception {
        UUID id = UUID.randomUUID();
        when(updateTitle.execute(any())).thenReturn(title(id));
        mvc.perform(put("/internal/books/" + id).contentType(MediaType.APPLICATION_JSON).content(VALID)
                        .with(withKey("TitleController/update")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        verify(updateTitle).execute(new UpdateTitleCommand(id, "Clean Code", "Robert Martin", "9780132350884",
                new BigDecimal("250000")));
    }

    @Test
    void suaDauSachKhongCoKhoaHoacKhoaKhacBi403KhongTokenBi401() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/internal/books/" + id).contentType(MediaType.APPLICATION_JSON).content(VALID)
                        .with(noPermission()))
                .andExpect(status().isForbidden());
        mvc.perform(put("/internal/books/" + id).contentType(MediaType.APPLICATION_JSON).content(VALID)
                        .with(withKey("TitleController/create")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/internal/books/" + id).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(updateTitle);
    }

    @Test
    void suaDauSachSaiDuLieuTra400VaIdSaiTra400() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/internal/books/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"author\":\"B\",\"coverPrice\":-1}").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(put("/internal/books/khong-phai-uuid").contentType(MediaType.APPLICATION_JSON).content(VALID)
                        .with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(updateTitle);
    }

    @Test
    void suaDauSachKhongThayTra404TrungIsbnTra409() throws Exception {
        UUID id = UUID.randomUUID();
        when(updateTitle.execute(any())).thenThrow(new TitleNotFoundException(id));
        mvc.perform(put("/internal/books/" + id).contentType(MediaType.APPLICATION_JSON).content(VALID).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TITLE_NOT_FOUND"));
        org.mockito.Mockito.doThrow(new DuplicateIsbnException("9780132350884")).when(updateTitle).execute(any());
        mvc.perform(put("/internal/books/" + id).contentType(MediaType.APPLICATION_JSON).content(VALID).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ISBN"));
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
    void timKiemTraTenantIdTrongPhanTuVaTokenThuongBoQuaThamSoTenantId() throws Exception {
        when(searchTitles.execute(null, false)).thenReturn(List.of(title(UUID.randomUUID())));
        mvc.perform(get("/internal/books").param("tenantId", "khong-phai-uuid").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value(TENANT.toString()));
    }

    @Test
    void platformAdminKhongTenantTimKiemKhongCanKhoaVaKhongCanTenantId() throws Exception {
        when(searchTitles.execute(null, false)).thenReturn(List.of(title(UUID.randomUUID())));
        mvc.perform(get("/internal/books").with(platformAdmin())).andExpect(status().isOk());
        mvc.perform(get("/internal/books").param("tenantId", UUID.randomUUID().toString()).with(platformAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminTenantIdSaiDinhDangTra400() throws Exception {
        mvc.perform(get("/internal/books").param("tenantId", "abc").with(platformAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
        verifyNoInteractions(searchTitles);
    }

    @Test
    void platformAdminKhongTenantVanBi403ODocTheoIdVaGhi() throws Exception {
        mvc.perform(get("/internal/books/" + UUID.randomUUID()).with(platformAdmin()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(VALID)
                        .with(platformAdmin()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(getTitle, createTitle);
    }

    @Test
    void timKiemAvailableKhongPhaiBooleanTra400() throws Exception {
        mvc.perform(get("/internal/books").param("available", "abc").with(admin()))
                .andExpect(status().isBadRequest());
    }

    // --- lookup ---

    private static String lookupBody(int n) {
        return "{\"ids\":[" + java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
    }

    @Test
    void lookupVoiKhoaLookupTra200VaChiCacTruongToiThieu() throws Exception {
        UUID id = UUID.randomUUID();
        when(lookupTitles.execute(List.of(id))).thenReturn(List.of(new lms.catalog.catalog.application.TitleLookupView(
                id, "Clean Code", "Robert Martin", "9780132350884", new BigDecimal("250000"), "Mo ta", true)));
        mvc.perform(post("/internal/books/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"]}").with(withKey("TitleController/lookup")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].name").value("Clean Code"))
                .andExpect(jsonPath("$[0].author").value("Robert Martin"))
                .andExpect(jsonPath("$[0].isbn").value("9780132350884"))
                .andExpect(jsonPath("$[0].coverPrice").value(250000))
                .andExpect(jsonPath("$[0].description").value("Mo ta"))
                .andExpect(jsonPath("$[0].hasPreview").value(true))
                .andExpect(jsonPath("$[0].previewText").doesNotExist())
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[0].availableCopies").doesNotExist());
    }

    @Test
    void lookupKhoaKhacHoacChuaGanBi403KhongTokenBi401() throws Exception {
        String body = lookupBody(1);
        mvc.perform(post("/internal/books/lookup").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(withKey("TitleController/get")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/books/lookup").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(noPermission()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/books/lookup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(lookupTitles);
    }

    @Test
    void lookupAdminKhongCanKhoaVaToiDa100Id() throws Exception {
        when(lookupTitles.execute(any())).thenReturn(List.of());
        mvc.perform(post("/internal/books/lookup").contentType(MediaType.APPLICATION_JSON).content(lookupBody(100))
                        .with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void lookupQua100IdHoacRongHoacThieuIdTra400() throws Exception {
        for (String body : new String[] {lookupBody(101), "{\"ids\":[]}", "{}", "{\"ids\":[null]}",
                "{\"ids\":[\"khong-phai-uuid\"]}"}) {
            mvc.perform(post("/internal/books/lookup").contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(admin()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(lookupTitles);
    }

    @Test
    void lookupTokenKhongCoTenantBi403() throws Exception {
        mvc.perform(post("/internal/books/lookup").contentType(MediaType.APPLICATION_JSON).content(lookupBody(1))
                        .with(jwt().jwt(j -> j.claim("admin", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    // --- lookup-by-isbn (ADR-0024) ---

    private static String isbnBody(int n) {
        return "{\"isbns\":[" + java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "\"I" + i + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
    }

    @Test
    void lookupByIsbnVoiKhoaTra200() throws Exception {
        UUID id = UUID.randomUUID();
        when(lookupByIsbn.execute(List.of("9780132350884"))).thenReturn(List.of(
                new lms.catalog.catalog.application.TitleLookupView(id, "Clean Code", "Robert Martin",
                        "9780132350884", new BigDecimal("250000"), null, false)));
        mvc.perform(post("/internal/books/lookup-by-isbn").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbns\":[\"9780132350884\"]}").with(withKey("TitleController/lookupByIsbn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].isbn").value("9780132350884"))
                .andExpect(jsonPath("$[0].tenantId").doesNotExist());
    }

    @Test
    void lookupByIsbnKhoaKhacBi403KhongTokenBi401TokenDichVuStorefrontBi403() throws Exception {
        String body = isbnBody(1);
        mvc.perform(post("/internal/books/lookup-by-isbn").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(withKey("TitleController/lookup")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/books/lookup-by-isbn").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(noPermission()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/books/lookup-by-isbn").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/books/lookup-by-isbn").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(svcToken()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(lookupByIsbn);
    }

    @Test
    void lookupByIsbnToiDa5000Va400KhiSaiRangBuoc() throws Exception {
        when(lookupByIsbn.execute(any())).thenReturn(List.of());
        mvc.perform(post("/internal/books/lookup-by-isbn").contentType(MediaType.APPLICATION_JSON)
                        .content(isbnBody(5000)).with(admin()))
                .andExpect(status().isOk());
        for (String body : new String[] {isbnBody(5001), "{\"isbns\":[]}", "{}", "{\"isbns\":[\" \"]}",
                "{\"isbns\":[null]}", "{\"isbns\":[\"123456789012345678901\"]}"}) {
            mvc.perform(post("/internal/books/lookup-by-isbn").contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(admin()))
                    .andExpect(status().isBadRequest());
        }
    }

    // --- preview / mô tả (ADR-0011 2.12) ---

    private static RequestPostProcessor svcToken() {
        return jwt().jwt(j -> j.claim("svc", "customer-api").claim("tenantId", TENANT.toString())
                .claim("modules", List.of("SALES")).claim("permissions", List.of()));
    }

    private static lms.catalog.catalog.application.TitlePreviewResponse previewOf(UUID id) {
        return new lms.catalog.catalog.application.TitlePreviewResponse(id, "Clean Code", "Mo ta", "Doan doc thu");
    }

    @Test
    void docPreviewVoiKhoaPreviewTra200KhongIsbnGiaBiaTenant() throws Exception {
        UUID id = UUID.randomUUID();
        when(getPreview.execute(id)).thenReturn(previewOf(id));
        mvc.perform(get("/internal/books/" + id + "/preview").with(withKey("TitleController/preview")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Clean Code"))
                .andExpect(jsonPath("$.description").value("Mo ta"))
                .andExpect(jsonPath("$.previewText").value("Doan doc thu"))
                .andExpect(jsonPath("$.isbn").doesNotExist())
                .andExpect(jsonPath("$.coverPrice").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    void docPreviewKhoaKhacBi403KhongTokenBi401() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/internal/books/" + id + "/preview").with(withKey("TitleController/updatePreview")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/books/" + id + "/preview").with(noPermission())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/books/" + id + "/preview")).andExpect(status().isUnauthorized());
        verifyNoInteractions(getPreview);
    }

    @Test
    void tokenDichVuDocDuocPreviewLookupVaLookupTheoTuKhoa() throws Exception {
        UUID id = UUID.randomUUID();
        when(getPreview.execute(id)).thenReturn(previewOf(id));
        when(lookupTitles.execute(any())).thenReturn(List.of());
        when(lookupByKeyword.execute("clean")).thenReturn(List.of());
        mvc.perform(get("/internal/books/" + id + "/preview").with(svcToken())).andExpect(status().isOk());
        mvc.perform(post("/internal/books/lookup").contentType(MediaType.APPLICATION_JSON).content(lookupBody(1))
                        .with(svcToken())).andExpect(status().isOk());
        mvc.perform(get("/internal/books/lookup").param("keyword", "clean").with(svcToken()))
                .andExpect(status().isOk());
        verify(lookupByKeyword).execute("clean");
    }

    @Test
    void tokenDichVuBi403OUpdatePreviewVaCacEndpointKhac() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/internal/books/" + id + "/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"a\",\"previewText\":\"b\"}").with(svcToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/books/" + id).with(svcToken())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/books").with(svcToken())).andExpect(status().isForbidden());
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(VALID).with(svcToken()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(updatePreview, getTitle, searchTitles, createTitle);
    }

    @Test
    void capNhatPreviewVoiKhoaUpdatePreviewTra200() throws Exception {
        UUID id = UUID.randomUUID();
        when(updatePreview.execute(id, "Mo ta", "Doan doc thu")).thenReturn(previewOf(id));
        mvc.perform(put("/internal/books/" + id + "/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Mo ta\",\"previewText\":\"Doan doc thu\"}")
                        .with(withKey("TitleController/updatePreview")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewText").value("Doan doc thu"));
    }

    @Test
    void capNhatPreviewKhoaPreviewHoacChuaGanBi403KhongTokenBi401() throws Exception {
        UUID id = UUID.randomUUID();
        String body = "{\"description\":\"a\"}";
        mvc.perform(put("/internal/books/" + id + "/preview").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(withKey("TitleController/preview"))).andExpect(status().isForbidden());
        mvc.perform(put("/internal/books/" + id + "/preview").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(noPermission())).andExpect(status().isForbidden());
        mvc.perform(put("/internal/books/" + id + "/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(updatePreview);
    }

    @Test
    void capNhatPreviewQuaDaiTra400TitlePreviewTooLong() throws Exception {
        UUID id = UUID.randomUUID();
        when(updatePreview.execute(any(), any(), any()))
                .thenThrow(new lms.catalog.catalog.domain.TitlePreviewTooLongException("Đoạn đọc thử", 2000));
        mvc.perform(put("/internal/books/" + id + "/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewText\":\"x\"}").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TITLE_PREVIEW_TOO_LONG"));
    }

    @Test
    void capNhatPreviewKhongChanEmojiDuoi2000CodePointNhungChanThoThanQuaLon() throws Exception {
        UUID id = UUID.randomUUID();
        when(updatePreview.execute(any(), any(), any())).thenReturn(previewOf(id));
        // 1500 emoji = 3000 đơn vị UTF-16 nhưng 1500 code point: không bị chặn thô (không có @Size 2000 ở previewText)
        String emoji = "\\uD83D\\uDE00".repeat(1500);
        mvc.perform(put("/internal/books/" + id + "/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewText\":\"" + emoji + "\"}").with(admin()))
                .andExpect(status().isOk());
        mvc.perform(put("/internal/books/" + id + "/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewText\":\"" + "x".repeat(8001) + "\"}").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(put("/internal/books/" + id + "/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + "x".repeat(2001) + "\"}").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void previewKhongThayTra404() throws Exception {
        UUID id = UUID.randomUUID();
        when(getPreview.execute(id)).thenThrow(new TitleNotFoundException(id));
        mvc.perform(get("/internal/books/" + id + "/preview").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TITLE_NOT_FOUND"));
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

    // --- thông tin bổ sung (V7) ---

    private static final String WITH_EXTRAS = "{\"name\":\"Harry Potter\",\"author\":\"J.K. Rowling\",\"coverPrice\":120000,"
            + "\"subtitle\":\"  Phụ đề \",\"publicationYear\":2017,\"edition\":\"Tái bản lần thứ 8\",\"language\":\"vi\","
            + "\"pageCount\":368,\"format\":\"PAPERBACK\",\"translator\":\"Lý Lan\",\"series\":\"Harry Potter\","
            + "\"seriesNumber\":1,\"coverImageUrl\":\"https://img.example/hp.jpg\",\"tags\":[\"Giả tưởng\",\"giả tưởng\",\"Phép thuật\"]}";

    private static String with(String field) {
        return WITH_EXTRAS.substring(0, WITH_EXTRAS.length() - 1) + "," + field + "}";
    }

    @Test
    void taoDauSachChuyenThongTinBoSungDaChuanHoaVaTraVeDayDuTruong() throws Exception {
        UUID id = UUID.randomUUID();
        TitleExtras expected = new TitleExtras("Phụ đề", 2017, "Tái bản lần thứ 8", "vi", 368, TitleFormat.PAPERBACK,
                "Lý Lan", "Harry Potter", 1, "https://img.example/hp.jpg", List.of("Giả tưởng", "Phép thuật"));
        when(createTitle.execute(any())).thenReturn(new TitleResponse(id, "Harry Potter", "J.K. Rowling", null,
                new BigDecimal("120000"), 0, TENANT, null, null, null, null, List.of(), "Phụ đề", 2017,
                "Tái bản lần thứ 8", "vi", 368, TitleFormat.PAPERBACK, "Lý Lan", "Harry Potter", 1,
                "https://img.example/hp.jpg", List.of("Giả tưởng", "Phép thuật")));

        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(WITH_EXTRAS)
                        .with(withKey("TitleController/create")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtitle").value("Phụ đề"))
                .andExpect(jsonPath("$.publicationYear").value(2017))
                .andExpect(jsonPath("$.edition").value("Tái bản lần thứ 8"))
                .andExpect(jsonPath("$.language").value("vi"))
                .andExpect(jsonPath("$.pageCount").value(368))
                .andExpect(jsonPath("$.format").value("PAPERBACK"))
                .andExpect(jsonPath("$.translator").value("Lý Lan"))
                .andExpect(jsonPath("$.series").value("Harry Potter"))
                .andExpect(jsonPath("$.seriesNumber").value(1))
                .andExpect(jsonPath("$.coverImageUrl").value("https://img.example/hp.jpg"))
                .andExpect(jsonPath("$.tags[0]").value("Giả tưởng"))
                .andExpect(jsonPath("$.tags.length()").value(2));
        verify(createTitle).execute(new CreateTitleCommand("Harry Potter", "J.K. Rowling", null,
                new BigDecimal("120000"), null, null, null, expected));
    }

    @Test
    void suaDauSachChuyenThongTinBoSung() throws Exception {
        UUID id = UUID.randomUUID();
        when(updateTitle.execute(any())).thenReturn(title(id));
        mvc.perform(put("/internal/books/" + id).contentType(MediaType.APPLICATION_JSON).content(WITH_EXTRAS)
                        .with(withKey("TitleController/update")))
                .andExpect(status().isOk());
        verify(updateTitle).execute(new UpdateTitleCommand(id, "Harry Potter", "J.K. Rowling", null,
                new BigDecimal("120000"), null, null, null,
                new TitleExtras("Phụ đề", 2017, "Tái bản lần thứ 8", "vi", 368, TitleFormat.PAPERBACK, "Lý Lan",
                        "Harry Potter", 1, "https://img.example/hp.jpg", List.of("Giả tưởng", "Phép thuật"))));
    }

    @Test
    void bodyCuKhongCoTruongMoiVanHopLeVaExtrasRong() throws Exception {
        when(createTitle.execute(any())).thenReturn(title(UUID.randomUUID()));
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(VALID)
                        .with(withKey("TitleController/create")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtitle").doesNotExist())
                .andExpect(jsonPath("$.tags").isEmpty());
    }

    @Test
    void thongTinBoSungSaiRangBuocTra400() throws Exception {
        for (String bad : List.of(
                "\"language\":\"VI\"", "\"language\":\"v\"", "\"language\":\"vietnamese\"",
                "\"publicationYear\":999", "\"publicationYear\":3001", "\"pageCount\":0", "\"pageCount\":20001",
                "\"seriesNumber\":0", "\"format\":\"AUDIO\"", "\"coverImageUrl\":\"ftp://x.example/a.jpg\"",
                "\"coverImageUrl\":\"javascript:alert(1)\"", "\"subtitle\":\"" + "a".repeat(256) + "\"",
                "\"edition\":\"" + "a".repeat(51) + "\"",
                "\"tags\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\"]",
                "\"tags\":[\"" + "a".repeat(51) + "\"]")) {
            mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(with(bad))
                            .with(admin()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(createTitle);
    }

    @Test
    void soTapKhongCoBoSachTra422VaKhongGoiUseCase() throws Exception {
        String body = "{\"name\":\"A\",\"author\":\"B\",\"coverPrice\":1,\"seriesNumber\":2}";
        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(body).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
        verifyNoInteractions(createTitle);
    }
}
