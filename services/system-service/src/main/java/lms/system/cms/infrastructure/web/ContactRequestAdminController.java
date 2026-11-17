package lms.system.cms.infrastructure.web;

import jakarta.validation.Valid;
import java.util.UUID;
import lms.security.permission.PlatformAdminOnly;
import lms.system.cms.application.ManageContactRequestsUseCase;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Xử lý yêu cầu liên hệ: chỉ platform admin. Phản hồi có dữ liệu cá nhân (null sau khi ẩn danh), không log. */
@PlatformAdminOnly
@RestController
@RequestMapping("/internal/cms/contact-requests")
public class ContactRequestAdminController {

    private final ManageContactRequestsUseCase contactRequests;

    public ContactRequestAdminController(ManageContactRequestsUseCase contactRequests) {
        this.contactRequests = contactRequests;
    }

    @GetMapping
    public CmsResponses.ContactPage search(CmsRequests.ContactSearchQuery query) {
        return CmsResponses.ContactPage.from(contactRequests.search(query.toCriteria()));
    }

    @GetMapping("/{id}")
    public CmsResponses.ContactDetail get(@PathVariable UUID id) {
        return CmsResponses.ContactDetail.from(contactRequests.get(id));
    }

    @PutMapping("/{id}/status")
    public CmsResponses.ContactDetail changeStatus(@PathVariable UUID id,
                                                   @Valid @RequestBody CmsRequests.ChangeContactStatus request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return CmsResponses.ContactDetail.from(contactRequests.changeStatus(id, request.status(), request.version(),
                CmsPageAdminController.actor(jwt)));
    }

    /** Ẩn danh ngay (người gửi đòi xóa). Idempotent. */
    @PostMapping("/{id}/anonymization")
    public CmsResponses.ContactDetail anonymize(@PathVariable UUID id) {
        return CmsResponses.ContactDetail.from(contactRequests.anonymize(id));
    }
}
