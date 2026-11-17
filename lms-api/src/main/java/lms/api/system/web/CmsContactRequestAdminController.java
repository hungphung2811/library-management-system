package lms.api.system.web;

import lms.api.system.client.CmsClient;
import lms.api.system.dto.CmsDtos.ContactRequestPageView;
import lms.api.system.dto.CmsDtos.ContactRequestSearchQuery;
import lms.api.system.dto.CmsDtos.ContactRequestView;
import lms.api.system.dto.CmsDtos.UpdateContactRequestStatusRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hộp yêu cầu liên hệ cho platform admin (có dữ liệu cá nhân). system-service tự kiểm quyền; lms-api không kiểm. */
@RestController
@RequestMapping("/api/cms/contact-requests")
public class CmsContactRequestAdminController {

    private final CmsClient cms;

    public CmsContactRequestAdminController(CmsClient cms) {
        this.cms = cms;
    }

    @GetMapping
    public ContactRequestPageView search(ContactRequestSearchQuery query) {
        return cms.searchContactRequests(query);
    }

    @GetMapping("/{id}")
    public ContactRequestView get(@PathVariable UUID id) {
        return cms.getContactRequest(id);
    }

    @PutMapping("/{id}/status")
    public ContactRequestView updateStatus(@PathVariable UUID id,
                                           @Valid @RequestBody UpdateContactRequestStatusRequest request) {
        return cms.updateContactRequestStatus(id, request);
    }

    /** Ẩn danh ngay khi người gửi đòi xóa (ADR-0011 2.10): fullName/email/phone/message thành null. */
    @PostMapping("/{id}/anonymization")
    public ContactRequestView anonymize(@PathVariable UUID id) {
        return cms.anonymizeContactRequest(id);
    }
}
