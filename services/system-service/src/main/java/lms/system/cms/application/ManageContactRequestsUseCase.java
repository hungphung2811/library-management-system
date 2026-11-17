package lms.system.cms.application;

import java.time.Clock;
import java.util.UUID;
import lms.system.cms.domain.CmsErrorCode;
import lms.system.cms.domain.CmsException;
import lms.system.cms.domain.ContactRequest;
import lms.system.cms.domain.ContactStatus;
import org.springframework.transaction.annotation.Transactional;

/** Xử lý yêu cầu liên hệ (platform admin): tìm, xem, đổi trạng thái, ẩn danh ngay. */
public class ManageContactRequestsUseCase {

    private final ContactRequestRepositoryPort repository;
    private final Clock clock;

    public ManageContactRequestsUseCase(ContactRequestRepositoryPort repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ContactRequestRepositoryPort.Page<ContactRequest> search(ContactRequestRepositoryPort.Criteria criteria) {
        return repository.search(criteria);
    }

    @Transactional(readOnly = true)
    public ContactRequest get(UUID id) {
        return find(id);
    }

    @Transactional
    public ContactRequest changeStatus(UUID id, ContactStatus status, long version, UUID by) {
        ContactRequest current = find(id);
        current.requireVersion(version);
        return repository.save(current.changeStatus(status, by, clock.instant()));
    }

    /** Ẩn danh ngay theo yêu cầu của người gửi. Idempotent: đã ẩn danh thì trả nguyên trạng. */
    @Transactional
    public ContactRequest anonymize(UUID id) {
        ContactRequest current = find(id);
        return current.isAnonymized() ? current : repository.save(current.anonymize(clock.instant()));
    }

    private ContactRequest find(UUID id) {
        return repository.findById(id).orElseThrow(
                () -> new CmsException(CmsErrorCode.CONTACT_REQUEST_NOT_FOUND, "Không tìm thấy yêu cầu liên hệ"));
    }
}
