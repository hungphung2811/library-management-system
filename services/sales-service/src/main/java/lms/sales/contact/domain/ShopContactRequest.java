package lms.sales.contact.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Yêu cầu liên hệ của khách gửi cửa hàng (ADR-0015). Domain thuần: chuẩn hóa/kiểm đầu vào khi {@link #submit}, máy
 * trạng thái {@link #changeStatus}, ẩn danh {@link #anonymize} (dùng chung cho job và nhân viên, idempotent).
 * Bất biến: chưa ẩn danh thì userId, tên, email, nội dung có giá trị; đã ẩn danh thì cả năm trường dữ liệu cá nhân
 * là null. Không in dữ liệu cá nhân trong {@code toString}.
 */
public final class ShopContactRequest {

    public static final int MAX_NAME = 100;
    public static final int MAX_EMAIL = 254;
    public static final int MAX_PHONE = 20;
    public static final int MAX_MESSAGE = 2000;
    private static final int MIN_PHONE_DIGITS = 6;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+().\\- ]+$");

    private final UUID id;
    private final UUID tenantId;
    private final UUID customerUserId;
    private final ContactTopic topic;
    private final String message;
    private final String contactName;
    private final String contactEmail;
    private final String contactPhone;
    private final Instant consentAt;
    private final ContactStatus status;
    private final long version;
    private final Instant createdAt;
    private final Instant closedAt;
    private final Instant piiAnonymizedAt;

    private ShopContactRequest(UUID id, UUID tenantId, UUID customerUserId, ContactTopic topic, String message,
                               String contactName, String contactEmail, String contactPhone, Instant consentAt,
                               ContactStatus status, long version, Instant createdAt, Instant closedAt,
                               Instant piiAnonymizedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.customerUserId = customerUserId;
        this.topic = topic;
        this.message = message;
        this.contactName = contactName;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.consentAt = consentAt;
        this.status = status;
        this.version = version;
        this.createdAt = createdAt;
        this.closedAt = closedAt;
        this.piiAnonymizedAt = piiAnonymizedAt;
    }

    /** Tạo mới từ đầu vào thô: các trường được chuẩn hóa và kiểm (R3), consent phải true (CONSENT_REQUIRED). */
    public static ShopContactRequest submit(UUID id, UUID tenantId, UUID customerUserId, ContactTopic topic,
                                            String fullName, String email, String phone, String message,
                                            Boolean consent, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(customerUserId, "customerUserId");
        if (topic == null) {
            throw SalesException.invalidRequest("topic: bắt buộc");
        }
        String name = ContactText.required(fullName, false, MAX_NAME, "fullName");
        String mail = ContactText.required(email, false, MAX_EMAIL, "email");
        if (!EMAIL.matcher(mail).matches()) {
            throw SalesException.invalidRequest("email: sai định dạng");
        }
        String tel = normalizePhone(phone);
        String text = ContactText.required(message, true, MAX_MESSAGE, "message");
        if (!Boolean.TRUE.equals(consent)) {
            throw new SalesException(ErrorCode.CONSENT_REQUIRED, "Cần đồng ý để cửa hàng lưu thông tin liên hệ");
        }
        return new ShopContactRequest(id, tenantId, customerUserId, topic, text, name, mail, tel, now,
                ContactStatus.NEW, 0, now, null, null);
    }

    /** Nạp lại từ lưu trữ (không kiểm lại đầu vào). */
    public static ShopContactRequest restore(UUID id, UUID tenantId, UUID customerUserId, ContactTopic topic,
                                             String message, String contactName, String contactEmail,
                                             String contactPhone, Instant consentAt, ContactStatus status,
                                             long version, Instant createdAt, Instant closedAt,
                                             Instant piiAnonymizedAt) {
        return new ShopContactRequest(id, tenantId, customerUserId, topic, message, contactName, contactEmail,
                contactPhone, consentAt, status, version, createdAt, closedAt, piiAnonymizedAt);
    }

    private static String normalizePhone(String phone) {
        String p = ContactText.normalize(phone, false, "phone");
        if (p == null || p.isEmpty()) {
            return null;
        }
        ContactText.checkLength(p, MAX_PHONE, "phone");
        long digits = p.chars().filter(c -> c >= '0' && c <= '9').count();
        if (!PHONE.matcher(p).matches() || digits < MIN_PHONE_DIGITS) {
            throw SalesException.invalidRequest("phone: sai định dạng");
        }
        return p;
    }

    /** Đổi trạng thái theo R6; sai chuyển trạng thái thì 409 SHOP_CONTACT_STATE_CONFLICT. */
    public ShopContactRequest changeStatus(ContactStatus to, Instant now) {
        if (to == null || !status.canTransitionTo(to)) {
            throw stateConflict("Không thể chuyển trạng thái yêu cầu liên hệ từ " + status + " sang " + to);
        }
        return new ShopContactRequest(id, tenantId, customerUserId, topic, message, contactName, contactEmail,
                contactPhone, consentAt, to, version, createdAt, to == ContactStatus.CLOSED ? now : closedAt,
                piiAnonymizedAt);
    }

    /** Ẩn danh: xóa năm trường dữ liệu cá nhân; đã ẩn danh thì trả chính nó (idempotent). */
    public ShopContactRequest anonymize(Instant now) {
        if (isAnonymized()) {
            return this;
        }
        return new ShopContactRequest(id, tenantId, null, topic, null, null, null, null, consentAt, status, version,
                createdAt, closedAt, now);
    }

    public static SalesException stateConflict(String message) {
        return new SalesException(ErrorCode.SHOP_CONTACT_STATE_CONFLICT, message);
    }

    public static SalesException notFound() {
        return new SalesException(ErrorCode.SHOP_CONTACT_NOT_FOUND, "Không tìm thấy yêu cầu liên hệ");
    }

    public boolean isAnonymized() {
        return piiAnonymizedAt != null;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID customerUserId() { return customerUserId; }
    public ContactTopic topic() { return topic; }
    public String message() { return message; }
    public String contactName() { return contactName; }
    public String contactEmail() { return contactEmail; }
    public String contactPhone() { return contactPhone; }
    public Instant consentAt() { return consentAt; }
    public ContactStatus status() { return status; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant closedAt() { return closedAt; }
    public Instant piiAnonymizedAt() { return piiAnonymizedAt; }

    @Override
    public String toString() {
        return "ShopContactRequest[id=" + id + ", status=" + status + ", ***]";
    }
}
