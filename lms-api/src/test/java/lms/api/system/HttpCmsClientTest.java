package lms.api.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import lms.api.support.TokenRelayInterceptor;
import lms.api.system.client.http.HttpCmsClient;
import lms.api.system.dto.CmsDtos.CmsPageSummary;
import lms.api.system.dto.CmsDtos.ContactRequestSummary;
import lms.api.system.dto.CmsDtos.PublishCmsPageRequest;
import lms.api.system.dto.CmsDtos.ContactRequestPageView;
import lms.api.system.dto.CmsDtos.ContactRequestSearchQuery;
import lms.api.system.dto.CmsDtos.ContactRequestView;
import lms.api.system.dto.CmsDtos.CreateCmsPageRequest;
import lms.api.system.dto.CmsDtos.UpdateCmsPageRequest;
import lms.api.system.dto.CmsDtos.UpdateContactRequestStatusRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** HttpCmsClient gọi đúng /internal/cms/**, chuyển tiếp token và bỏ qua trường lạ. */
class HttpCmsClientTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void goiApiQuanTriCmsDungDuongDanVaChuyenTiepToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://system")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCmsClient client = new HttpCmsClient(builder.build());
        Jwt jwt = Jwt.withTokenValue("token-admin").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        UUID id = UUID.randomUUID();
        String page = "{\"id\":\"" + id + "\",\"slug\":\"gioi-thieu\",\"title\":\"T\",\"bodyMarkdown\":\"x\","
                + "\"status\":\"PUBLISHED\",\"sortOrder\":1,\"version\":2,\"truongMoi\":1}";
        String contact = "{\"id\":\"" + id + "\",\"fullName\":null,\"email\":null,\"organization\":\"O\","
                + "\"status\":\"NEW\",\"version\":1,\"anonymizedAt\":\"2026-01-01T00:00:00Z\"}";

        server.expect(requestTo("http://system/internal/cms/pages")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-admin"))
                .andRespond(withSuccess("[" + page + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/pages/" + id)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(page, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/pages")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.slug").value("gioi-thieu"))
                .andRespond(withSuccess(page, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/pages/" + id)).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.version").value(2))
                .andRespond(withSuccess(page, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/pages/" + id + "/publication"))
                .andExpect(method(HttpMethod.POST)).andExpect(jsonPath("$.version").value(2))
                .andRespond(withSuccess(page, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/pages/" + id + "/publication"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess(page, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/pages/" + id)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());
        server.expect(requestTo("http://system/internal/cms/contact-requests?status=NEW&page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[" + contact + "],\"total\":1,\"page\":0,\"size\":20}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/contact-requests/" + id)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(contact, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/contact-requests/" + id + "/status"))
                .andExpect(method(HttpMethod.PUT)).andExpect(jsonPath("$.status").value("HANDLED"))
                .andExpect(jsonPath("$.version").value(1))
                .andRespond(withSuccess(contact, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/contact-requests/" + id + "/anonymization"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(contact, MediaType.APPLICATION_JSON));

        List<CmsPageSummary> pages = client.listPages();
        assertEquals("gioi-thieu", pages.get(0).slug());
        assertEquals("PUBLISHED", pages.get(0).status());
        assertEquals("PUBLISHED", client.getPage(id).status());
        client.createPage(new CreateCmsPageRequest("gioi-thieu", "T", "x", 1));
        client.updatePage(id, new UpdateCmsPageRequest("T", "x", 1, 2L));
        client.publishPage(id, new PublishCmsPageRequest(2L));
        client.unpublishPage(id);
        client.deletePage(id);

        ContactRequestPageView found = client.searchContactRequests(
                new ContactRequestSearchQuery("NEW", null, null, 0, 20));
        assertEquals(1, found.total());
        ContactRequestSummary anonymized = found.items().get(0);
        assertNull(anonymized.email());
        assertTrue(anonymized.anonymizedAt() != null);
        client.getContactRequest(id);
        client.updateContactRequestStatus(id, new UpdateContactRequestStatusRequest("HANDLED", 1L));
        client.anonymizeContactRequest(id);
        server.verify();
    }

    @Test
    void toStringKhongLoDuLieuCaNhan() {
        ContactRequestView v = new ContactRequestView(UUID.randomUUID(), "Nguyen An", "an@example.com", "0901",
                "O", "LIBRARY", "tin nhan", null, "NEW", 0L, null, null, null, null);
        assertTrue(!v.toString().contains("an@example.com") && !v.toString().contains("Nguyen"));
    }
}
