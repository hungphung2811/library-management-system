package lms.system.cms.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.system.cms.domain.ContactRequest;
import lms.system.cms.domain.ContactStatus;

public interface ContactRequestRepositoryPort {

    /** Lưu; xung đột phiên bản ném CmsException(CONTACT_REQUEST_VERSION_CONFLICT). */
    ContactRequest save(ContactRequest request);

    Optional<ContactRequest> findById(UUID id);

    Page<ContactRequest> search(Criteria criteria);

    /** Tối đa {@code limit} yêu cầu chưa ẩn danh, tạo trước {@code cutoff}, cũ nhất trước. */
    List<ContactRequest> findDueForAnonymization(Instant cutoff, int limit);

    /** Điều kiện lọc; trường null là không lọc. {@code from} gồm, {@code to} không gồm (theo created_at). */
    record Criteria(ContactStatus status, Instant from, Instant to, int page, int size) {
    }

    record Page<T>(List<T> items, int page, int size, long total) {
    }
}
