package lms.system.cms.infrastructure.web;

import jakarta.validation.Valid;
import java.util.List;
import lms.security.permission.PublicEndpoint;
import lms.system.cms.application.PublishedCmsPagesUseCase;
import lms.system.cms.application.SubmitContactRequestUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API nội bộ cho customer-api (ADR-0011). {@code @PublicEndpoint} và {@code permitAll} đúng ba đường dẫn trong
 * SecurityConfiguration: không cần JWT, không có tenant. Chỉ đọc nội dung đã xuất bản và tạo yêu cầu liên hệ.
 */
@PublicEndpoint
@RestController
@RequestMapping("/internal/cms/public")
public class CmsPublicController {

    private final PublishedCmsPagesUseCase publishedPages;
    private final SubmitContactRequestUseCase submitContact;

    public CmsPublicController(PublishedCmsPagesUseCase publishedPages, SubmitContactRequestUseCase submitContact) {
        this.publishedPages = publishedPages;
        this.submitContact = submitContact;
    }

    @GetMapping("/pages")
    public List<CmsResponses.PublicPageSummary> listPages() {
        return publishedPages.list().stream().map(CmsResponses.PublicPageSummary::from).toList();
    }

    @GetMapping("/pages/{slug}")
    public CmsResponses.PublicPage getPage(@PathVariable String slug) {
        return CmsResponses.PublicPage.from(publishedPages.get(slug));
    }

    @PostMapping("/contact-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public void submitContactRequest(@Valid @RequestBody CmsRequests.SubmitContact request) {
        submitContact.execute(request.toCommand());
    }
}
