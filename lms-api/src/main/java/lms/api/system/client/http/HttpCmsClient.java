package lms.api.system.client.http;

import lms.api.system.client.CmsClient;
import lms.api.system.dto.CmsDtos.CmsPageSummary;
import lms.api.system.dto.CmsDtos.CmsPageView;
import lms.api.system.dto.CmsDtos.PublishCmsPageRequest;
import lms.api.system.dto.CmsDtos.ContactRequestPageView;
import lms.api.system.dto.CmsDtos.ContactRequestSearchQuery;
import lms.api.system.dto.CmsDtos.ContactRequestView;
import lms.api.system.dto.CmsDtos.CreateCmsPageRequest;
import lms.api.system.dto.CmsDtos.UpdateCmsPageRequest;
import lms.api.system.dto.CmsDtos.UpdateContactRequestStatusRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Gọi API quản trị /internal/cms/** của system-service. Token và correlation id do RestClient chuyển tiếp. */
public class HttpCmsClient implements CmsClient {

    private final RestClient client;

    public HttpCmsClient(RestClient client) {
        this.client = client;
    }

    @Override
    public List<CmsPageSummary> listPages() {
        List<CmsPageSummary> result = client.get().uri("/internal/cms/pages").retrieve()
                .body(new ParameterizedTypeReference<List<CmsPageSummary>>() { });
        return result == null ? List.of() : result;
    }

    @Override
    public CmsPageView getPage(UUID id) {
        return client.get().uri("/internal/cms/pages/{id}", id).retrieve().body(CmsPageView.class);
    }

    @Override
    public CmsPageView createPage(CreateCmsPageRequest request) {
        return client.post().uri("/internal/cms/pages").body(request).retrieve().body(CmsPageView.class);
    }

    @Override
    public CmsPageView updatePage(UUID id, UpdateCmsPageRequest request) {
        return client.put().uri("/internal/cms/pages/{id}", id).body(request).retrieve().body(CmsPageView.class);
    }

    @Override
    public CmsPageView publishPage(UUID id, PublishCmsPageRequest request) {
        return client.post().uri("/internal/cms/pages/{id}/publication", id).body(request).retrieve()
                .body(CmsPageView.class);
    }

    @Override
    public CmsPageView unpublishPage(UUID id) {
        return client.delete().uri("/internal/cms/pages/{id}/publication", id).retrieve().body(CmsPageView.class);
    }

    @Override
    public void deletePage(UUID id) {
        client.delete().uri("/internal/cms/pages/{id}", id).retrieve().toBodilessEntity();
    }

    @Override
    public ContactRequestPageView searchContactRequests(ContactRequestSearchQuery q) {
        ContactRequestPageView result = client.get().uri(b -> {
            b.path("/internal/cms/contact-requests");
            if (q.status() != null && !q.status().isBlank()) b.queryParam("status", q.status());
            if (q.from() != null && !q.from().isBlank()) b.queryParam("from", q.from());
            if (q.to() != null && !q.to().isBlank()) b.queryParam("to", q.to());
            if (q.page() != null) b.queryParam("page", q.page());
            if (q.size() != null) b.queryParam("size", q.size());
            return b.build();
        }).retrieve().body(ContactRequestPageView.class);
        return result == null ? new ContactRequestPageView(List.of(), 0, 0, 0) : result;
    }

    @Override
    public ContactRequestView getContactRequest(UUID id) {
        return client.get().uri("/internal/cms/contact-requests/{id}", id).retrieve().body(ContactRequestView.class);
    }

    @Override
    public ContactRequestView updateContactRequestStatus(UUID id, UpdateContactRequestStatusRequest request) {
        return client.put().uri("/internal/cms/contact-requests/{id}/status", id).body(request).retrieve()
                .body(ContactRequestView.class);
    }

    @Override
    public ContactRequestView anonymizeContactRequest(UUID id) {
        return client.post().uri("/internal/cms/contact-requests/{id}/anonymization", id).retrieve()
                .body(ContactRequestView.class);
    }
}
