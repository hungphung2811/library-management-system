package lms.api.system;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.api.config.SecurityConfiguration;
import lms.api.system.client.CmsClient;
import lms.api.system.dto.CmsDtos.CmsPageSummary;
import lms.api.system.dto.CmsDtos.CmsPageView;
import lms.api.system.dto.CmsDtos.ContactRequestSummary;
import lms.api.system.dto.CmsDtos.PublishCmsPageRequest;
import lms.api.system.dto.CmsDtos.ContactRequestPageView;
import lms.api.system.dto.CmsDtos.ContactRequestSearchQuery;
import lms.api.system.dto.CmsDtos.ContactRequestView;
import lms.api.system.dto.CmsDtos.CreateCmsPageRequest;
import lms.api.system.dto.CmsDtos.UpdateCmsPageRequest;
import lms.api.system.dto.CmsDtos.UpdateContactRequestStatusRequest;
import lms.api.system.web.CmsContactRequestAdminController;
import lms.api.system.web.CmsPageAdminController;
import lms.api.web.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

/** Hợp đồng FE của /api/cms/**: xác thực, validate, mã 201/204, ánh xạ query và chuyển nguyên lỗi CMS_* của system-service. */
@WebMvcTest({CmsPageAdminController.class, CmsContactRequestAdminController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class CmsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean CmsClient cms;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private static CmsPageView page(UUID id) {
        return new CmsPageView(id, "gioi-thieu", "Giới thiệu", "# Xin chào", "DRAFT", null, null, null, 10, 0L, T, T,
                null);
    }

    private static CmsPageSummary summary(UUID id) {
        return new CmsPageSummary(id, "gioi-thieu", "Giới thiệu", "DRAFT", 10, null, 0L, T);
    }

    private static ContactRequestSummary contactSummary(UUID id) {
        return new ContactRequestSummary(id, "An", "an@example.com", "Thư viện A", "LIBRARY", "NEW", T, null);
    }

    private static ContactRequestView contact(UUID id) {
        return new ContactRequestView(id, "An", "an@example.com", null, "Thư viện A", "LIBRARY", "Cần demo", T, "NEW",
                0L, T, null, null, null);
    }

    private static HttpClientErrorException downstream(HttpStatus status, String code) {
        String body = "{\"code\":\"" + code + "\",\"message\":\"x\",\"details\":[]}";
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/cms/pages")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cms/contact-requests")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cms/contact-requests/" + UUID.randomUUID() + "/anonymization"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(cms);
    }

    @Test
    void lietKeVaXemTrang() throws Exception {
        UUID id = UUID.randomUUID();
        when(cms.listPages()).thenReturn(List.of(summary(id)));
        when(cms.getPage(id)).thenReturn(page(id));
        mvc.perform(get("/api/cms/pages").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("gioi-thieu"))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].bodyMarkdown").doesNotExist());
        mvc.perform(get("/api/cms/pages/" + id).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bodyMarkdown").value("# Xin chào"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.publicationStatus").doesNotExist());
    }

    @Test
    void taoTrangTra201KemLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(cms.createPage(any(CreateCmsPageRequest.class))).thenReturn(page(id));
        mvc.perform(post("/api/cms/pages").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"gioi-thieu\",\"title\":\"Giới thiệu\",\"bodyMarkdown\":\"# A\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/cms/pages/" + id));
    }

    @Test
    void taoTrangSaiSlugHoacThieuTruongBi400KhongGoiService() throws Exception {
        mvc.perform(post("/api/cms/pages").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"Sai Slug\",\"title\":\"T\",\"bodyMarkdown\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/cms/pages").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"ok\",\"bodyMarkdown\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/cms/pages").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"ok\",\"title\":\"T\",\"bodyMarkdown\":\"" + "a".repeat(50001) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(cms, never()).createPage(any());
    }

    @Test
    void suaTrangPhaiCoVersion() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/api/cms/pages/" + id).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"bodyMarkdown\":\"x\",\"sortOrder\":1}"))
                .andExpect(status().isBadRequest());

        when(cms.updatePage(eq(id), any(UpdateCmsPageRequest.class))).thenReturn(page(id));
        mvc.perform(put("/api/cms/pages/" + id).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"bodyMarkdown\":\"x\",\"sortOrder\":1,\"version\":0}"))
                .andExpect(status().isOk());
    }

    @Test
    void xuatBanGoXuatBanVaXoaTrang() throws Exception {
        UUID id = UUID.randomUUID();
        when(cms.publishPage(eq(id), any(PublishCmsPageRequest.class))).thenReturn(page(id));
        when(cms.unpublishPage(id)).thenReturn(page(id));
        mvc.perform(post("/api/cms/pages/" + id + "/publication").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":3}")).andExpect(status().isOk());
        mvc.perform(delete("/api/cms/pages/" + id + "/publication").with(jwt())).andExpect(status().isOk());
        mvc.perform(delete("/api/cms/pages/" + id).with(jwt())).andExpect(status().isNoContent());
        verify(cms).publishPage(id, new PublishCmsPageRequest(3L));
        verify(cms).unpublishPage(id);
        verify(cms).deletePage(id);
    }

    @Test
    void xuatBanThieuVersionBi400KhongGoiService() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/cms/pages/" + id + "/publication").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/cms/pages/" + id + "/publication").with(jwt())).andExpect(status().isBadRequest());
        verify(cms, never()).publishPage(any(), any());
    }

    @Test
    void maLoiCmsChuyenNguyen() throws Exception {
        UUID id = UUID.randomUUID();
        when(cms.getPage(id)).thenThrow(downstream(HttpStatus.NOT_FOUND, "CMS_PAGE_NOT_FOUND"));
        mvc.perform(get("/api/cms/pages/" + id).with(jwt()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CMS_PAGE_NOT_FOUND"));

        when(cms.createPage(any(CreateCmsPageRequest.class)))
                .thenThrow(downstream(HttpStatus.CONFLICT, "CMS_SLUG_DUPLICATED"));
        mvc.perform(post("/api/cms/pages").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"a\",\"title\":\"T\",\"bodyMarkdown\":\"x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CMS_SLUG_DUPLICATED"));

        when(cms.updatePage(eq(id), any(UpdateCmsPageRequest.class)))
                .thenThrow(downstream(HttpStatus.CONFLICT, "CMS_VERSION_CONFLICT"));
        mvc.perform(put("/api/cms/pages/" + id).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"bodyMarkdown\":\"x\",\"sortOrder\":1,\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CMS_VERSION_CONFLICT"));

        when(cms.publishPage(eq(id), any(PublishCmsPageRequest.class)))
                .thenThrow(downstream(HttpStatus.CONFLICT, "CMS_VERSION_CONFLICT"));
        mvc.perform(post("/api/cms/pages/" + id + "/publication").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CMS_VERSION_CONFLICT"));

        when(cms.unpublishPage(id)).thenThrow(downstream(HttpStatus.FORBIDDEN, "FORBIDDEN"));
        mvc.perform(delete("/api/cms/pages/" + id + "/publication").with(jwt()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

        UUID other = UUID.randomUUID();
        when(cms.getPage(other)).thenThrow(downstream(HttpStatus.BAD_REQUEST, "CMS_PAGE_INVALID"));
        mvc.perform(get("/api/cms/pages/" + other).with(jwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CMS_PAGE_INVALID"));
    }

    @Test
    void timYeuCauLienHeGomQuerySangRecord() throws Exception {
        UUID id = UUID.randomUUID();
        when(cms.searchContactRequests(any(ContactRequestSearchQuery.class)))
                .thenReturn(new ContactRequestPageView(List.of(contactSummary(id)), 1, 0, 20));
        mvc.perform(get("/api/cms/contact-requests").param("status", "NEW").param("from", "2026-01-01T00:00:00Z")
                        .param("page", "0").param("size", "20").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].email").value("an@example.com"))
                .andExpect(jsonPath("$.items[0].status").value("NEW"))
                .andExpect(jsonPath("$.items[0].message").doesNotExist());

        ArgumentCaptor<ContactRequestSearchQuery> q = ArgumentCaptor.forClass(ContactRequestSearchQuery.class);
        verify(cms).searchContactRequests(q.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                new ContactRequestSearchQuery("NEW", "2026-01-01T00:00:00Z", null, 0, 20), q.getValue());
    }

    @Test
    void doiTrangThaiPhaiCoStatusVaVersion() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/api/cms/contact-requests/" + id + "/status").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HANDLED\"}"))
                .andExpect(status().isBadRequest());

        when(cms.updateContactRequestStatus(eq(id), any(UpdateContactRequestStatusRequest.class)))
                .thenReturn(contact(id));
        mvc.perform(put("/api/cms/contact-requests/" + id + "/status").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HANDLED\",\"version\":0}"))
                .andExpect(status().isOk());
    }

    @Test
    void anDanhNgayVaLoiDaAnDanhChuyenNguyen() throws Exception {
        UUID id = UUID.randomUUID();
        ContactRequestView anonymized = new ContactRequestView(id, null, null, null, "Thư viện A", "LIBRARY", null, T,
                "NEW", 1L, T, null, null, T);
        when(cms.anonymizeContactRequest(id)).thenReturn(anonymized);
        mvc.perform(post("/api/cms/contact-requests/" + id + "/anonymization").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.anonymizedAt").exists());

        when(cms.updateContactRequestStatus(eq(id), any(UpdateContactRequestStatusRequest.class)))
                .thenThrow(downstream(HttpStatus.CONFLICT, "CONTACT_REQUEST_ANONYMIZED"));
        mvc.perform(put("/api/cms/contact-requests/" + id + "/status").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HANDLED\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTACT_REQUEST_ANONYMIZED"));

        when(cms.updateContactRequestStatus(eq(id), any(UpdateContactRequestStatusRequest.class)))
                .thenThrow(downstream(HttpStatus.CONFLICT, "CONTACT_STATUS_TRANSITION_INVALID"));
        mvc.perform(put("/api/cms/contact-requests/" + id + "/status").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"NEW\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTACT_STATUS_TRANSITION_INVALID"));

        when(cms.getContactRequest(id)).thenThrow(downstream(HttpStatus.NOT_FOUND, "CONTACT_REQUEST_NOT_FOUND"));
        mvc.perform(get("/api/cms/contact-requests/" + id).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTACT_REQUEST_NOT_FOUND"));
    }
}
