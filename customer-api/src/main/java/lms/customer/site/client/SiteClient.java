package lms.customer.site.client;

import java.util.List;
import lms.customer.site.dto.SiteDtos.ContactSubmission;
import lms.customer.site.dto.SiteDtos.PageSummaryView;
import lms.customer.site.dto.SiteDtos.PageView;

/** Cổng sang context cms của system-service (đọc trang đã xuất bản, ghi yêu cầu liên hệ). */
public interface SiteClient {

    List<PageSummaryView> listPages();

    /** Ném PublicApiException 404 CMS_PAGE_NOT_FOUND khi trang không có hoặc chưa xuất bản. */
    PageView getPage(String slug);

    void submitContactRequest(ContactSubmission submission);
}
