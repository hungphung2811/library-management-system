package lms.api.system.client;

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

/** Cổng sang API quản trị CMS (/internal/cms/**, không gồm /internal/cms/public/**) của system-service. */
public interface CmsClient {

    List<CmsPageSummary> listPages();

    CmsPageView getPage(UUID id);

    CmsPageView createPage(CreateCmsPageRequest request);

    CmsPageView updatePage(UUID id, UpdateCmsPageRequest request);

    CmsPageView publishPage(UUID id, PublishCmsPageRequest request);

    CmsPageView unpublishPage(UUID id);

    void deletePage(UUID id);

    ContactRequestPageView searchContactRequests(ContactRequestSearchQuery query);

    ContactRequestView getContactRequest(UUID id);

    ContactRequestView updateContactRequestStatus(UUID id, UpdateContactRequestStatusRequest request);

    ContactRequestView anonymizeContactRequest(UUID id);
}
