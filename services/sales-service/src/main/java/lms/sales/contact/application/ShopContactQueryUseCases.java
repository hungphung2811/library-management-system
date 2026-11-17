package lms.sales.contact.application;

import java.time.Instant;
import java.util.UUID;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.shared.application.PageResult;
import org.springframework.transaction.annotation.Transactional;

/** Đọc yêu cầu liên hệ cho nhân viên. Id của tenant khác hoặc không có: 404 SHOP_CONTACT_NOT_FOUND (cùng thân). */
public class ShopContactQueryUseCases {

    private final ShopContactRepository repository;

    public ShopContactQueryUseCases(ShopContactRepository repository) {
        this.repository = repository;
    }

    /** Liên hệ đầy đủ (không che); khóa riêng ở controller. Đã ẩn danh thì các trường là null. */
    public record ContactInfo(String fullName, String email, String phone, Instant piiAnonymizedAt) {
        @Override
        public String toString() {
            return "ContactInfo[***]";
        }
    }

    @Transactional(readOnly = true)
    public PageResult<ShopContactRequest> search(UUID tenantId, ShopContactRepository.SearchQuery query) {
        return repository.search(tenantId, query);
    }

    @Transactional(readOnly = true)
    public ShopContactRequest get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(ShopContactRequest::notFound);
    }

    @Transactional(readOnly = true)
    public ContactInfo contact(UUID tenantId, UUID id) {
        ShopContactRequest r = get(tenantId, id);
        return new ContactInfo(r.contactName(), r.contactEmail(), r.contactPhone(), r.piiAnonymizedAt());
    }
}
