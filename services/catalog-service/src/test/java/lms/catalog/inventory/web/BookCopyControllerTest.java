package lms.catalog.inventory.web;

import static org.mockito.ArgumentMatchers.any;
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
import lms.catalog.config.SecurityConfiguration;
import lms.catalog.inventory.application.AddCopyCommand;
import lms.catalog.inventory.application.AddCopyUseCase;
import lms.catalog.inventory.application.ChangeCopyStatusUseCase;
import lms.catalog.inventory.application.CopyDetailsResponse;
import lms.catalog.inventory.application.CopyResponse;
import lms.catalog.inventory.application.GetCopyUseCase;
import lms.catalog.inventory.application.ListCopiesUseCase;
import lms.catalog.inventory.domain.CopyNotFoundException;
import lms.catalog.inventory.domain.CopyStatus;
import lms.catalog.inventory.domain.CopyTitleNotFoundException;
import lms.catalog.inventory.domain.DuplicateBarcodeException;
import lms.catalog.inventory.domain.IllegalCopyTransitionException;
import lms.catalog.inventory.domain.InvalidCopyException;
import lms.catalog.inventory.infrastructure.web.BookCopyController;
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

/** WebMvc cho BookCopyController: xác thực, phân quyền theo khóa Controller/method, validation, ánh xạ lỗi domain. */
@WebMvcTest(BookCopyController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class BookCopyControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID TITLE_ID = UUID.randomUUID();
    private static final String ADD_VALID = addBody("\"" + TITLE_ID + "\"", "\"BC-001\"");

    @Autowired MockMvc mvc;

    @MockitoBean AddCopyUseCase addCopy;
    @MockitoBean ListCopiesUseCase listCopies;
    @MockitoBean GetCopyUseCase getCopy;
    @MockitoBean ChangeCopyStatusUseCase changeStatus;
    @MockitoBean lms.catalog.inventory.application.MoveCopyShelfLocationUseCase moveShelfLocation;
    @MockitoBean lms.catalog.inventory.application.LookupCopiesUseCase lookupCopies;
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

    private static CopyResponse copy(UUID id, CopyStatus status) {
        return new CopyResponse(id, TITLE_ID, "BC-001", status, TENANT);
    }

    private static RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.claim("platformAdmin", true));
    }

    private static String addBody(String titleId, String barcode) {
        return "{\"titleId\":" + titleId + ",\"barcode\":" + barcode + "}";
    }

    // --- xác thực / phân quyền ---

    @Test
    void khongTokenBi401ChoCaNamEndpoint() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/internal/book-copies/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/book-copies/by-barcode/BC-001")).andExpect(status().isUnauthorized());
        mvc.perform(put("/internal/book-copies/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"LOANED\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON).content(ADD_VALID))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/book-copies").param("titleId", TITLE_ID.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(addCopy, listCopies, getCopy, changeStatus);
    }

    @Test
    void khoaChuaGanBi403ChoNonAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/internal/book-copies/" + id).with(noPermission())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/book-copies/by-barcode/BC-001").with(noPermission()))
                .andExpect(status().isForbidden());
        mvc.perform(put("/internal/book-copies/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"LOANED\"}").with(noPermission())).andExpect(status().isForbidden());
        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON).content(ADD_VALID)
                .with(noPermission())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/book-copies").param("titleId", TITLE_ID.toString()).with(noPermission()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(addCopy, listCopies, getCopy, changeStatus);
    }

    @Test
    void khoaCuaEndpointKhacKhongDuQuyen() throws Exception {
        mvc.perform(put("/internal/book-copies/" + UUID.randomUUID() + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOANED\"}")
                        .with(withKey("BookCopyController/get")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenKhongCoTenantBi403KeCaAdmin() throws Exception {
        mvc.perform(get("/internal/book-copies/" + UUID.randomUUID()).with(jwt().jwt(j -> j.claim("admin", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    // --- get theo id / mã vạch ---

    @Test
    void layBanSaoTheoIdVoiKhoaGet() throws Exception {
        UUID id = UUID.randomUUID();
        when(getCopy.byId(id)).thenReturn(new CopyDetailsResponse(id, TITLE_ID, "Clean Code", "BC-001",
                CopyStatus.AVAILABLE, new BigDecimal("250000")));
        mvc.perform(get("/internal/book-copies/" + id).with(withKey("BookCopyController/get")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titleName").value("Clean Code"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.coverPrice").value(250000));
    }

    @Test
    void banSaoKhongTonTaiTheoIdTra404() throws Exception {
        UUID id = UUID.randomUUID();
        when(getCopy.byId(id)).thenThrow(new CopyNotFoundException(id));
        mvc.perform(get("/internal/book-copies/" + id).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COPY_NOT_FOUND"));
    }

    @Test
    void idKhongPhaiUuidTra400() throws Exception {
        mvc.perform(get("/internal/book-copies/abc").with(admin())).andExpect(status().isBadRequest());
    }

    @Test
    void layBanSaoTheoMaVachVoiKhoaGetByBarcode() throws Exception {
        UUID id = UUID.randomUUID();
        when(getCopy.byBarcode("BC-001")).thenReturn(new CopyDetailsResponse(id, TITLE_ID, "Clean Code", "BC-001",
                CopyStatus.LOANED, new BigDecimal("250000")));
        mvc.perform(get("/internal/book-copies/by-barcode/BC-001").with(withKey("BookCopyController/getByBarcode")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.barcode").value("BC-001"))
                .andExpect(jsonPath("$.status").value("LOANED"));
    }

    @Test
    void maVachKhongTonTaiTra404() throws Exception {
        when(getCopy.byBarcode("NOPE")).thenThrow(new CopyNotFoundException("NOPE"));
        mvc.perform(get("/internal/book-copies/by-barcode/NOPE").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COPY_NOT_FOUND"));
    }

    // --- đổi trạng thái ---

    @Test
    void doiTrangThaiVoiKhoaChangeStatus() throws Exception {
        UUID id = UUID.randomUUID();
        when(changeStatus.execute(id, CopyStatus.LOANED)).thenReturn(copy(id, CopyStatus.LOANED));
        mvc.perform(put("/internal/book-copies/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOANED\"}").with(withKey("BookCopyController/changeStatus")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOANED"));
        verify(changeStatus).execute(id, CopyStatus.LOANED);
    }

    @Test
    void doiTrangThaiSaiVongDoiTra409() throws Exception {
        UUID id = UUID.randomUUID();
        when(changeStatus.execute(id, CopyStatus.AVAILABLE))
                .thenThrow(new IllegalCopyTransitionException(CopyStatus.LOST, CopyStatus.AVAILABLE));
        mvc.perform(put("/internal/book-copies/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"AVAILABLE\"}").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COPY_STATUS_CONFLICT"));
    }

    @Test
    void doiTrangThaiBanSaoKhongTonTaiTra404() throws Exception {
        UUID id = UUID.randomUUID();
        when(changeStatus.execute(id, CopyStatus.LOST)).thenThrow(new CopyNotFoundException(id));
        mvc.perform(put("/internal/book-copies/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOST\"}").with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void doiTrangThaiValidationSaiTra400() throws Exception {
        UUID id = UUID.randomUUID();
        for (String body : new String[] {"{}", "{\"status\":null}"}) {
            mvc.perform(put("/internal/book-copies/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                            .content(body).with(admin()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(put("/internal/book-copies/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BROKEN\"}").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(changeStatus);
    }

    // --- thêm bản sao ---

    @Test
    void themBanSaoVoiKhoaAddTra201KemLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(addCopy.execute(any())).thenReturn(copy(id, CopyStatus.AVAILABLE));
        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON).content(ADD_VALID)
                        .with(withKey("BookCopyController/add")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/internal/book-copies/" + id))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        verify(addCopy).execute(new AddCopyCommand(TITLE_ID, "BC-001"));
    }

    @Test
    void themBanSaoValidationSaiTra400() throws Exception {
        String t = "\"" + TITLE_ID + "\"";
        String[] bodies = {
                "{\"barcode\":\"BC-001\"}",
                addBody(t, "null"),
                addBody(t, "\"   \""),
                addBody(t, "\"" + "x".repeat(65) + "\""),
                addBody("\"khong-phai-uuid\"", "\"BC\"")
        };
        for (String body : bodies) {
            mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(admin()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(addCopy);
    }

    @Test
    void themBanSaoBarcodeDai64KyTuHopLe() throws Exception {
        when(addCopy.execute(any())).thenReturn(copy(UUID.randomUUID(), CopyStatus.AVAILABLE));
        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON)
                        .content(addBody("\"" + TITLE_ID + "\"", "\"" + "x".repeat(64) + "\"")).with(admin()))
                .andExpect(status().isCreated());
    }

    @Test
    void trungMaVachTra409() throws Exception {
        when(addCopy.execute(any())).thenThrow(new DuplicateBarcodeException("BC-001"));
        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON).content(ADD_VALID)
                        .with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BARCODE"));
    }

    @Test
    void themBanSaoChoDauSachKhongTonTaiTra404() throws Exception {
        when(addCopy.execute(any())).thenThrow(new CopyTitleNotFoundException(TITLE_ID));
        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON).content(ADD_VALID)
                        .with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TITLE_NOT_FOUND"));
    }

    @Test
    void banSaoKhongHopLeTheoDomainTra422() throws Exception {
        when(addCopy.execute(any())).thenThrow(new InvalidCopyException("sai"));
        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON).content(ADD_VALID)
                        .with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COPY"));
    }

    // --- danh sách ---

    @Test
    void liietKeBanSaoTheoTitleIdVoiKhoaList() throws Exception {
        when(listCopies.execute(TITLE_ID)).thenReturn(List.of(copy(UUID.randomUUID(), CopyStatus.AVAILABLE),
                copy(UUID.randomUUID(), CopyStatus.LOANED)));
        mvc.perform(get("/internal/book-copies").param("titleId", TITLE_ID.toString())
                        .with(withKey("BookCopyController/list")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void platformAdminKhongTenantLietKeKhongCanKhoaVaTraTenantId() throws Exception {
        when(listCopies.execute(TITLE_ID)).thenReturn(List.of(copy(UUID.randomUUID(), CopyStatus.AVAILABLE)));
        mvc.perform(get("/internal/book-copies").param("titleId", TITLE_ID.toString()).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value(TENANT.toString()));
        mvc.perform(get("/internal/book-copies").param("titleId", TITLE_ID.toString())
                        .param("tenantId", UUID.randomUUID().toString()).with(platformAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminTenantIdSaiDinhDangTra400NhungTokenThuongBoQua() throws Exception {
        mvc.perform(get("/internal/book-copies").param("titleId", TITLE_ID.toString()).param("tenantId", "abc")
                        .with(platformAdmin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
        when(listCopies.execute(TITLE_ID)).thenReturn(List.of());
        mvc.perform(get("/internal/book-copies").param("titleId", TITLE_ID.toString()).param("tenantId", "abc")
                        .with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminKhongTenantVanBi403ODocTheoIdVaGhi() throws Exception {
        mvc.perform(get("/internal/book-copies/" + UUID.randomUUID()).with(platformAdmin()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        mvc.perform(get("/internal/book-copies/by-barcode/BC-001").with(platformAdmin()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON).content(ADD_VALID)
                        .with(platformAdmin()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(addCopy, getCopy);
    }

    @Test
    void liietKeThieuHoacSaiTitleIdTra400() throws Exception {
        mvc.perform(get("/internal/book-copies").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/book-copies").param("titleId", "abc").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(listCopies);
    }

    @Test
    void liietKeDauSachKhongTonTaiTra404() throws Exception {
        when(listCopies.execute(TITLE_ID)).thenThrow(new CopyTitleNotFoundException(TITLE_ID));
        mvc.perform(get("/internal/book-copies").param("titleId", TITLE_ID.toString()).with(admin()))
                .andExpect(status().isNotFound());
    }
}
