package lms.sales.contact.infrastructure.web;

import java.time.Instant;
import java.util.UUID;
import lms.sales.contact.application.ShopContactQueryUseCases;
import lms.sales.contact.domain.ContactStatus;
import lms.sales.contact.domain.ContactTopic;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.shared.domain.PersonalDataMasker;

/**
 * View allowlist của liên hệ (dựng tường minh từ domain, không trả entity). Danh sách và chi tiết CHE tên/email/điện
 * thoại; liên hệ đầy đủ chỉ qua {@link ContactView} (khóa getContact). Mọi record chứa dữ liệu cá nhân ghi đè
 * {@code toString}. Không có tenantId.
 */
public final class ShopContactViews {

    private ShopContactViews() {
    }

    public record MaskedContact(String name, String email, String phone) {
        @Override
        public String toString() {
            return "MaskedContact[***]";
        }
    }

    /** Danh sách: không có message, customerUserId. */
    public record SummaryView(UUID id, ContactTopic topic, ContactStatus status, Instant createdAt, Instant closedAt,
                              MaskedContact contact, Instant piiAnonymizedAt, long version) {
        public static SummaryView from(ShopContactRequest r) {
            return new SummaryView(r.id(), r.topic(), r.status(), r.createdAt(), r.closedAt(), masked(r),
                    r.piiAnonymizedAt(), r.version());
        }

        @Override
        public String toString() {
            return "SummaryView[id=" + id + ", ***]";
        }
    }

    /** Chi tiết: thêm message và customerUserId; liên hệ vẫn che. */
    public record DetailView(UUID id, ContactTopic topic, ContactStatus status, Instant createdAt, Instant closedAt,
                             MaskedContact contact, Instant piiAnonymizedAt, long version, String message,
                             UUID customerUserId) {
        public static DetailView from(ShopContactRequest r) {
            return new DetailView(r.id(), r.topic(), r.status(), r.createdAt(), r.closedAt(), masked(r),
                    r.piiAnonymizedAt(), r.version(), r.message(), r.customerUserId());
        }

        @Override
        public String toString() {
            return "DetailView[id=" + id + ", ***]";
        }
    }

    /** Liên hệ đầy đủ (khóa riêng). Đã ẩn danh: các trường null. */
    public record ContactView(String fullName, String email, String phone, Instant piiAnonymizedAt) {
        public static ContactView from(ShopContactQueryUseCases.ContactInfo c) {
            return new ContactView(c.fullName(), c.email(), c.phone(), c.piiAnonymizedAt());
        }

        @Override
        public String toString() {
            return "ContactView[***]";
        }
    }

    private static MaskedContact masked(ShopContactRequest r) {
        return new MaskedContact(PersonalDataMasker.maskName(r.contactName()),
                PersonalDataMasker.maskEmail(r.contactEmail()), PersonalDataMasker.maskPhone(r.contactPhone()));
    }
}
