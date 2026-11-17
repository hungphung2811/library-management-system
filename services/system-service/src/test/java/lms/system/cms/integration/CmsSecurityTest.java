package lms.system.cms.integration;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.system.cms.application.ContactRequestRepositoryPort;
import lms.system.cms.application.ManageCmsPagesUseCase;
import lms.system.cms.application.ManageContactRequestsUseCase;
import lms.system.cms.application.PublishedCmsPagesUseCase;
import lms.system.cms.application.SubmitContactRequestUseCase;
import lms.system.cms.domain.BusinessType;
import lms.system.cms.domain.CmsErrorCode;
import lms.system.cms.domain.CmsException;
import lms.system.cms.domain.CmsPage;
import lms.system.cms.domain.ContactRequest;
import lms.system.cms.domain.ContactStatus;
import lms.system.cms.infrastructure.web.CmsPageAdminController;
import lms.system.cms.infrastructure.web.CmsPublicController;
import lms.system.cms.infrastructure.web.ContactRequestAdminController;
import lms.system.config.SecurityConfiguration;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

/**
 * Xác thực + quyền + hợp đồng JSON + ánh xạ mã lỗi của API cms. Không cần Docker. Kiểm khóa bật lại (profile dev tắt);
 * quản trị chỉ platform admin, ba đường dẫn công khai không cần JWT và không đăng ký khóa.
 */
@WebMvcTest({CmsPublicController.class, CmsPageAdminController.class, ContactRequestAdminController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class CmsSecurityTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Autowired MockMvc mvc;

    @MockitoBean ManageCmsPagesUseCase managePages;
    @MockitoBean PublishedCmsPagesUseCase publishedPages;
    @MockitoBean SubmitContactRequestUseCase submitContact;
    @MockitoBean ManageContactRequestsUseCase manageContacts;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", true));
    }

    private static RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true)
                .claim("tenantId", TENANT.toString()));
    }

    private static RequestPostProcessor userWithKeys() {
        List<String> keys = List.of("CmsPageAdminController/list", "ContactRequestAdminController/search");
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                        .claim("permissions", keys))
                .authorities(keys.stream().map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new));
    }

    private static CmsPage page(String slug) {
        return new CmsPage(UUID.randomUUID(), slug, "Tiêu đề", "# nội dung nháp", null, null, null, 3, 2, NOW, NOW,
                null);
    }

    private static ContactRequest contact() {
        return new ContactRequest(UUID.randomUUID(), "Nguyễn A", "a@example.com", "0901", "Thư viện X",
                BusinessType.LIBRARY, "Lời nhắn", NOW, ContactStatus.NEW, 4, NOW, null, null, null);
    }

    private static final String CONTACT_BODY = """
            {"fullName":"Nguyễn A","email":"a@example.com","phone":"0901","organization":"Thư viện X",
             "businessType":"LIBRARY","message":"Muốn dùng thử","consent":true}""";

    // ---------- công khai: không JWT, không khóa ----------

    @Test
    void banDocCongKhaiKhongCanTokenVaKhongDangKyKhoa() throws Exception {
        when(publishedPages.list()).thenReturn(List.of(new PublishedCmsPagesUseCase.Summary("gioi-thieu", "Giới thiệu",
                1)));
        when(publishedPages.get("gioi-thieu")).thenReturn(new PublishedCmsPagesUseCase.Published("gioi-thieu",
                "Giới thiệu", "<h1>Xin chào</h1>", NOW));

        mvc.perform(get("/internal/cms/public/pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("gioi-thieu"))
                .andExpect(jsonPath("$[0].title").value("Giới thiệu"))
                .andExpect(jsonPath("$[0].sortOrder").value(1))
                .andExpect(jsonPath("$[0].bodyHtml").doesNotExist());
        mvc.perform(get("/internal/cms/public/pages/gioi-thieu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("gioi-thieu"))
                .andExpect(jsonPath("$.bodyHtml").value("<h1>Xin chào</h1>"))
                .andExpect(jsonPath("$.publishedAt").exists())
                .andExpect(jsonPath("$.bodyMarkdown").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());

        verifyNoInteractions(permissionKeyRegistrar);
    }

    @Test
    void trangChuaXuatBanTra404() throws Exception {
        when(publishedPages.get("nhap")).thenThrow(new CmsException(CmsErrorCode.CMS_PAGE_NOT_FOUND, "x"));

        mvc.perform(get("/internal/cms/public/pages/nhap"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CMS_PAGE_NOT_FOUND"));
    }

    @Test
    void guiFormLienHeKhongCanTokenTra201KhongThan() throws Exception {
        when(submitContact.execute(any())).thenReturn(UUID.randomUUID());

        mvc.perform(post("/internal/cms/public/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(CONTACT_BODY))
                .andExpect(status().isCreated())
                .andExpect(content().string(""));

        ArgumentCaptor<SubmitContactRequestUseCase.Command> cmd =
                ArgumentCaptor.forClass(SubmitContactRequestUseCase.Command.class);
        verify(submitContact).execute(cmd.capture());
        assertThat(cmd.getValue().businessType()).isEqualTo(BusinessType.LIBRARY);
        assertThat(cmd.getValue().consent()).isTrue();
        assertThat(cmd.getValue().email()).isEqualTo("a@example.com");
        verifyNoInteractions(permissionKeyRegistrar);
    }

    @Test
    void formThieuTruongHoacSaiLoaiHinhTra400KhongLoGiaTri() throws Exception {
        String bad = """
                {"fullName":"","email":"a@example.com","organization":"O","businessType":"SECRET_TYPE",
                 "message":"m","consent":true}""";

        mvc.perform(post("/internal/cms/public/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "SECRET_TYPE"))));
        mvc.perform(post("/internal/cms/public/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{khong phai json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(submitContact);
    }

    @Test
    void formQuaDaiTra400() throws Exception {
        String longMessage = "{\"fullName\":\"A\",\"email\":\"a@b.co\",\"organization\":\"O\","
                + "\"businessType\":\"OTHER\",\"message\":\"" + "m".repeat(2001) + "\",\"consent\":true}";

        mvc.perform(post("/internal/cms/public/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(longMessage))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(submitContact);
    }

    @Test
    void thieuDongYTra400ConsentRequired() throws Exception {
        when(submitContact.execute(any())).thenThrow(new CmsException(CmsErrorCode.CONSENT_REQUIRED, "Cần đồng ý"));

        mvc.perform(post("/internal/cms/public/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(CONTACT_BODY.replace("\"consent\":true", "\"consent\":false")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
    }

    @Test
    void chiBaDuongDanCongKhaiDuocMoCacDuongDanKhacVanCanToken() throws Exception {
        mvc.perform(post("/internal/cms/public/pages").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/cms/public/contact-requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/cms/public/pages/a/b")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/cms/pages")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/cms/contact-requests")).andExpect(status().isUnauthorized());
    }

    // ---------- quản trị: chỉ platform admin ----------

    @Test
    void tenantAdminVaNguoiCoKhoaBi403() throws Exception {
        for (RequestPostProcessor caller : List.of(tenantAdmin(), userWithKeys())) {
            mvc.perform(get("/internal/cms/pages").with(caller)).andExpect(status().isForbidden());
            mvc.perform(get("/internal/cms/pages/" + UUID.randomUUID()).with(caller)).andExpect(status().isForbidden());
            mvc.perform(post("/internal/cms/pages").with(caller).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"slug\":\"a\",\"title\":\"T\",\"bodyMarkdown\":\"b\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/internal/cms/pages/" + UUID.randomUUID()).with(caller))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/internal/cms/contact-requests").with(caller)).andExpect(status().isForbidden());
            mvc.perform(post("/internal/cms/contact-requests/" + UUID.randomUUID() + "/anonymization").with(caller))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(managePages, manageContacts);
        verifyNoInteractions(permissionKeyRegistrar);
    }

    @Test
    void platformAdminXemDanhSachVaChiTietTrang() throws Exception {
        CmsPage p = page("gioi-thieu");
        when(managePages.list()).thenReturn(List.of(new lms.system.cms.application.CmsPageRepositoryPort.AdminSummary(
                p.id(), p.slug(), p.title(), p.status(), p.sortOrder(), p.publishedAt(), p.version(), p.updatedAt())));
        when(managePages.get(p.id())).thenReturn(p);

        mvc.perform(get("/internal/cms/pages").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(p.id().toString()))
                .andExpect(jsonPath("$[0].slug").value("gioi-thieu"))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].bodyMarkdown").doesNotExist());
        mvc.perform(get("/internal/cms/pages/" + p.id()).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bodyMarkdown").value("# nội dung nháp"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.publishedBodyMarkdown").doesNotExist())
                .andExpect(jsonPath("$.sortOrder").value(3));
    }

    @Test
    void taoTrangTra201VaTruyenNguoiThucHien() throws Exception {
        UUID adminId = UUID.randomUUID();
        when(managePages.create(any())).thenReturn(page("moi"));

        mvc.perform(post("/internal/cms/pages")
                        .with(jwt().jwt(j -> j.subject(adminId.toString()).claim("platformAdmin", true)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"moi\",\"title\":\"Mới\",\"bodyMarkdown\":\"# a\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("moi"));

        ArgumentCaptor<ManageCmsPagesUseCase.CreateCommand> cmd =
                ArgumentCaptor.forClass(ManageCmsPagesUseCase.CreateCommand.class);
        verify(managePages).create(cmd.capture());
        assertThat(cmd.getValue().by()).isEqualTo(adminId);
        assertThat(cmd.getValue().sortOrder()).isZero();
        assertThat(cmd.getValue().slug()).isEqualTo("moi");
    }

    @Test
    void taoTrangThieuTruongBat400() throws Exception {
        mvc.perform(post("/internal/cms/pages").with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"\",\"title\":\"T\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(managePages);
    }

    @Test
    void suaXuatBanGoXuatBanXoa() throws Exception {
        UUID id = UUID.randomUUID();
        CmsPage p = page("a");
        when(managePages.update(any())).thenReturn(p);
        when(managePages.publish(eq(id), eq(7L), any())).thenReturn(p);
        when(managePages.unpublish(eq(id), any())).thenReturn(p);

        mvc.perform(put("/internal/cms/pages/" + id).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"bodyMarkdown\":\"b\",\"sortOrder\":2,\"version\":5}"))
                .andExpect(status().isOk());
        ArgumentCaptor<ManageCmsPagesUseCase.UpdateCommand> cmd =
                ArgumentCaptor.forClass(ManageCmsPagesUseCase.UpdateCommand.class);
        verify(managePages).update(cmd.capture());
        assertThat(cmd.getValue().id()).isEqualTo(id);
        assertThat(cmd.getValue().version()).isEqualTo(5);

        mvc.perform(post("/internal/cms/pages/" + id + "/publication").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":7}"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/cms/pages/" + id + "/publication").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/internal/cms/pages/" + id + "/publication").with(platformAdmin()))
                .andExpect(status().isOk());
        mvc.perform(delete("/internal/cms/pages/" + id).with(platformAdmin())).andExpect(status().isNoContent());
        verify(managePages).delete(id);
    }

    @Test
    void timYeuCauLienHeBindQueryVaTraDuLieu() throws Exception {
        ContactRequest c = contact();
        when(manageContacts.search(any())).thenReturn(new ContactRequestRepositoryPort.Page<>(List.of(c), 1, 20, 41));

        mvc.perform(get("/internal/cms/contact-requests").with(platformAdmin())
                        .param("status", "NEW").param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-02-01T00:00:00Z").param("page", "1").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(41))
                .andExpect(jsonPath("$.items[0].id").value(c.id().toString()))
                .andExpect(jsonPath("$.items[0].email").value("a@example.com"))
                .andExpect(jsonPath("$.items[0].organization").value("Thư viện X"))
                .andExpect(jsonPath("$.items[0].message").doesNotExist());

        ArgumentCaptor<ContactRequestRepositoryPort.Criteria> criteria =
                ArgumentCaptor.forClass(ContactRequestRepositoryPort.Criteria.class);
        verify(manageContacts).search(criteria.capture());
        assertThat(criteria.getValue().status()).isEqualTo(ContactStatus.NEW);
        assertThat(criteria.getValue().from()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(criteria.getValue().to()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(criteria.getValue().page()).isEqualTo(1);
        assertThat(criteria.getValue().size()).isEqualTo(100);
    }

    @Test
    void timYeuCauLienHeMacDinhVaQuerySaiTra400() throws Exception {
        when(manageContacts.search(any())).thenReturn(new ContactRequestRepositoryPort.Page<>(List.of(), 0, 20, 0));

        mvc.perform(get("/internal/cms/contact-requests").with(platformAdmin())).andExpect(status().isOk());
        ArgumentCaptor<ContactRequestRepositoryPort.Criteria> criteria =
                ArgumentCaptor.forClass(ContactRequestRepositoryPort.Criteria.class);
        verify(manageContacts).search(criteria.capture());
        assertThat(criteria.getValue().status()).isNull();
        assertThat(criteria.getValue().page()).isZero();
        assertThat(criteria.getValue().size()).isEqualTo(20);

        mvc.perform(get("/internal/cms/contact-requests").with(platformAdmin()).param("status", "KHONG_CO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/internal/cms/contact-requests").with(platformAdmin()).param("from", "hom-qua"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chiTietDoiTrangThaiVaAnDanh() throws Exception {
        ContactRequest c = contact();
        when(manageContacts.get(c.id())).thenReturn(c);
        when(manageContacts.changeStatus(eq(c.id()), eq(ContactStatus.HANDLED), eq(4L), any())).thenReturn(c);
        when(manageContacts.anonymize(c.id())).thenReturn(c.anonymize(NOW));

        mvc.perform(get("/internal/cms/contact-requests/" + c.id()).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Nguyễn A"))
                .andExpect(jsonPath("$.phone").value("0901"))
                .andExpect(jsonPath("$.message").value("Lời nhắn"))
                .andExpect(jsonPath("$.consentAt").exists())
                .andExpect(jsonPath("$.version").value(4));
        mvc.perform(put("/internal/cms/contact-requests/" + c.id() + "/status").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HANDLED\",\"version\":4}"))
                .andExpect(status().isOk());
        mvc.perform(put("/internal/cms/contact-requests/" + c.id() + "/status").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"XYZ\",\"version\":4}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/cms/contact-requests/" + c.id() + "/anonymization").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.organization").value("Thư viện X"))
                .andExpect(jsonPath("$.anonymizedAt").exists());
    }

    // ---------- ánh xạ mã lỗi ----------

    private void expectError(CmsErrorCode code, int httpStatus) throws Exception {
        org.mockito.Mockito.doThrow(new CmsException(code, "msg")).when(managePages).create(any());
        mvc.perform(post("/internal/cms/pages").with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"a\",\"title\":\"T\",\"bodyMarkdown\":\"b\"}"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code.name()));
    }

    @Test
    void maLoiNghiepVuAnhXaDungHttpStatus() throws Exception {
        expectError(CmsErrorCode.CMS_PAGE_NOT_FOUND, 404);
        expectError(CmsErrorCode.CONTACT_REQUEST_NOT_FOUND, 404);
        expectError(CmsErrorCode.CMS_SLUG_DUPLICATED, 409);
        expectError(CmsErrorCode.CMS_VERSION_CONFLICT, 409);
        expectError(CmsErrorCode.CONTACT_REQUEST_ANONYMIZED, 409);
        expectError(CmsErrorCode.CONTACT_REQUEST_VERSION_CONFLICT, 409);
        expectError(CmsErrorCode.CONTACT_STATUS_TRANSITION_INVALID, 409);
        expectError(CmsErrorCode.CMS_SLUG_INVALID, 400);
        expectError(CmsErrorCode.CMS_CONTENT_TOO_LONG, 400);
        expectError(CmsErrorCode.CMS_PAGE_INVALID, 400);
        expectError(CmsErrorCode.CONSENT_REQUIRED, 400);
        expectError(CmsErrorCode.CONTACT_REQUEST_INVALID, 400);
    }
}
