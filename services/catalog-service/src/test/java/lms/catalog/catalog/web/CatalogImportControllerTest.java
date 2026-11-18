package lms.catalog.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.catalog.catalog.application.imports.ReferenceImportHandler;
import lms.catalog.catalog.application.imports.TitleImportHandler;
import lms.catalog.catalog.infrastructure.web.AuthorImportController;
import lms.catalog.catalog.infrastructure.web.CategoryImportController;
import lms.catalog.catalog.infrastructure.web.PublisherImportController;
import lms.catalog.catalog.infrastructure.web.SeriesImportController;
import lms.catalog.catalog.infrastructure.web.ShelfLocationImportController;
import lms.catalog.catalog.infrastructure.web.TitleImportController;
import lms.catalog.config.SecurityConfiguration;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportViews;
import lms.imports.engine.ImportActor;
import lms.imports.engine.ImportWorkflow;
import lms.imports.web.ImportActorResolver;
import lms.security.permission.EndpointDescriptor;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Khóa phân quyền của 6 controller nhập catalog (ADR-0024 mục 4.1): upload và confirm là khóa
 * {@code <Lớp>ImportController/<method>} theo LỚP CON (không phải lớp cơ sở), các endpoint còn lại chỉ cần đăng nhập;
 * shelf-locations thuộc module LENDING. Kiểm khóa bật lại bằng {@code @TestPropertySource}.
 */
@WebMvcTest({TitleImportController.class, AuthorImportController.class, PublisherImportController.class,
        CategoryImportController.class, ShelfLocationImportController.class, SeriesImportController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class CatalogImportControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID IMPORT_ID = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean ImportWorkflow workflow;
    @MockitoBean ImportActorResolver actors;
    @MockitoBean TitleImportHandler titleHandler;
    @MockitoBean(name = "authorImportHandler") ReferenceImportHandler authorHandler;
    @MockitoBean(name = "publisherImportHandler") ReferenceImportHandler publisherHandler;
    @MockitoBean(name = "categoryImportHandler") ReferenceImportHandler categoryHandler;
    @MockitoBean(name = "shelfLocationImportHandler") ReferenceImportHandler shelfHandler;
    @MockitoBean(name = "seriesImportHandler") ReferenceImportHandler seriesHandler;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar registrar;

    @BeforeEach
    void stub() {
        when(actors.current()).thenReturn(new ImportActor(TENANT, "alice", Set.of("LENDING"), false));
        ImportViews.SessionView view = new ImportViews.SessionView(IMPORT_ID, "x", ImportSessionStatus.VALIDATED,
                "f.xlsx", Instant.now(), Instant.now().plusSeconds(1800), List.of(), null, null, null, "");
        when(workflow.upload(any(), any(), any(), any())).thenReturn(view);
        when(workflow.confirm(any(), any(), eq(IMPORT_ID))).thenReturn(new ImportViews.SessionView(IMPORT_ID, "x",
                ImportSessionStatus.COMMITTING, "f.xlsx", Instant.now(), Instant.now().plusSeconds(1800), List.of(),
                null, null, null, ""));
        when(workflow.template(any())).thenReturn(new byte[] {1, 2, 3});
    }

    private static RequestPostProcessor token(List<String> permissions, List<String> modules) {
        return jwt().jwt(j -> {
            j.claim("tenantId", TENANT.toString()).claim("permissions", permissions);
            if (modules != null) j.claim("modules", modules);
        }).authorities(permissions.stream().map(p -> (org.springframework.security.core.GrantedAuthority)
                new org.springframework.security.core.authority.SimpleGrantedAuthority(p)).toList());
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "a.xlsx", "application/octet-stream", new byte[] {1});
    }

    /** {tên lớp, đường dẫn}: 6 loại (ADR-0024 mục 3.4, thêm series ở ADR-0026), tên controller KHÔNG được đổi. */
    private static final String[][] TYPES = {
            {"TitleImportController", "titles"}, {"AuthorImportController", "authors"},
            {"PublisherImportController", "publishers"}, {"CategoryImportController", "categories"},
            {"ShelfLocationImportController", "shelf-locations"}, {"SeriesImportController", "series"}};

    @Test
    void uploadVaConfirmDungKhoaTheoTenLopCon() throws Exception {
        for (String[] t : TYPES) {
            String base = "/internal/imports/" + t[1];
            mvc.perform(multipart(base).file(file()).with(token(List.of(t[0] + "/upload"), List.of("LENDING"))))
                    .andExpect(status().isCreated());
            mvc.perform(post(base + "/" + IMPORT_ID + "/confirm").with(token(List.of(t[0] + "/confirm"), List.of("LENDING"))))
                    .andExpect(status().isAccepted());
        }
        ArgumentCaptor<EndpointDescriptor> captor = ArgumentCaptor.forClass(EndpointDescriptor.class);
        verify(registrar, atLeastOnce()).ensureRegistered(captor.capture());
        List<String> keys = captor.getAllValues().stream().map(EndpointDescriptor::key).distinct().toList();
        assertThat(keys).containsExactlyInAnyOrder(
                "TitleImportController/upload", "TitleImportController/confirm",
                "AuthorImportController/upload", "AuthorImportController/confirm",
                "PublisherImportController/upload", "PublisherImportController/confirm",
                "CategoryImportController/upload", "CategoryImportController/confirm",
                "ShelfLocationImportController/upload", "ShelfLocationImportController/confirm",
                "SeriesImportController/upload", "SeriesImportController/confirm");
    }

    @Test
    void thieuKhoaBi403() throws Exception {
        for (String[] t : TYPES) {
            String base = "/internal/imports/" + t[1];
            // có khóa của loại khác nhưng không có khóa của loại này
            mvc.perform(multipart(base).file(file()).with(token(List.of("OtherController/upload"), List.of("LENDING"))))
                    .andExpect(status().isForbidden());
            mvc.perform(post(base + "/" + IMPORT_ID + "/confirm").with(token(List.of(t[0] + "/upload"), List.of("LENDING"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void khoaUploadKhongThayChoKhoaConfirm() throws Exception {
        mvc.perform(post("/internal/imports/authors/" + IMPORT_ID + "/confirm")
                        .with(token(List.of("AuthorImportController/upload"), List.of("LENDING"))))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/internal/imports/authors").file(file())
                        .with(token(List.of("AuthorImportController/confirm"), List.of("LENDING"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void taiMauChiCanDangNhap() throws Exception {
        for (String[] t : TYPES) {
            mvc.perform(get("/internal/imports/" + t[1] + "/template").with(token(List.of(), List.of("LENDING"))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void viTriKeThuocLendingConCacLoaiLoiThiKhong() throws Exception {
        RequestPostProcessor salesOnly = token(List.of("ShelfLocationImportController/upload", "TitleImportController/upload"),
                List.of("SALES"));
        mvc.perform(multipart("/internal/imports/shelf-locations").file(file()).with(salesOnly))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        mvc.perform(get("/internal/imports/shelf-locations/template").with(salesOnly))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/internal/imports/titles").file(file()).with(salesOnly))
                .andExpect(status().isCreated());
        mvc.perform(get("/internal/imports/authors/template").with(salesOnly)).andExpect(status().isOk());
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/internal/imports/titles/template")).andExpect(status().isUnauthorized());
    }
}
