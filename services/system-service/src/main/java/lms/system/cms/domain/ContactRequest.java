package lms.system.cms.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Yêu cầu liên hệ/dùng thử từ trang công khai. Họ tên, email, điện thoại, lời nhắn là dữ liệu cá nhân: sau thời hạn
 * lưu giữ (hoặc khi platform admin ra lệnh) được ẩn danh bằng {@link #anonymize}. Bất biến. {@code toString} không in
 * dữ liệu cá nhân.
 */
public final class ContactRequest {

    public static final int FULL_NAME_MAX = 100;
    public static final int EMAIL_MAX = 254;
    public static final int PHONE_MAX = 20;
    public static final int ORGANIZATION_MAX = 200;
    public static final int MESSAGE_MAX = 2000;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UUID id;
    private final String fullName;
    private final String email;
    private final String phone;
    private final String organization;
    private final BusinessType businessType;
    private final String message;
    private final Instant consentAt;
    private final ContactStatus status;
    private final long version;
    private final Instant createdAt;
    private final UUID handledBy;
    private final Instant handledAt;
    private final Instant anonymizedAt;

    public ContactRequest(UUID id, String fullName, String email, String phone, String organization,
                          BusinessType businessType, String message, Instant consentAt, ContactStatus status,
                          long version, Instant createdAt, UUID handledBy, Instant handledAt, Instant anonymizedAt) {
        this.id = Objects.requireNonNull(id);
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.organization = organization;
        this.businessType = businessType;
        this.message = message;
        this.consentAt = consentAt;
        this.status = status;
        this.version = version;
        this.createdAt = createdAt;
        this.handledBy = handledBy;
        this.handledAt = handledAt;
        this.anonymizedAt = anonymizedAt;
    }

    /** Tiếp nhận yêu cầu mới (trạng thái NEW). Thiếu đồng ý thì CONSENT_REQUIRED. */
    public static ContactRequest submit(UUID id, String fullName, String email, String phone, String organization,
                                        BusinessType businessType, String message, boolean consent, Instant now) {
        if (!consent) {
            throw new CmsException(CmsErrorCode.CONSENT_REQUIRED, "Cần đồng ý để chúng tôi liên hệ");
        }
        String name = required(fullName, FULL_NAME_MAX, "Họ tên", false);
        String mail = required(email, EMAIL_MAX, "Email", false);
        if (!EMAIL.matcher(mail).matches()) {
            throw invalid("Email không hợp lệ");
        }
        String org = required(organization, ORGANIZATION_MAX, "Tổ chức", false);
        String msg = required(message, MESSAGE_MAX, "Lời nhắn", true);
        String tel = phone == null || phone.isBlank() ? null : phone.trim();
        if (tel != null && (tel.length() > PHONE_MAX || TextRules.hasControlChars(tel, false))) {
            throw invalid("Số điện thoại tối đa " + PHONE_MAX + " ký tự, không chứa ký tự điều khiển");
        }
        if (businessType == null) {
            throw invalid("Loại hình không được rỗng");
        }
        return new ContactRequest(id, name, mail, tel, org, businessType, msg, now, ContactStatus.NEW, 0, now, null,
                null, null);
    }

    public boolean isAnonymized() {
        return anonymizedAt != null;
    }

    /** Đổi trạng thái xử lý. Đã ẩn danh thì không sửa; bước không cho phép thì CONTACT_STATUS_TRANSITION_INVALID. */
    public ContactRequest changeStatus(ContactStatus next, UUID by, Instant now) {
        if (isAnonymized()) {
            throw new CmsException(CmsErrorCode.CONTACT_REQUEST_ANONYMIZED, "Yêu cầu đã được ẩn danh");
        }
        if (next == null || !status.allowedNext().contains(next)) {
            throw new CmsException(CmsErrorCode.CONTACT_STATUS_TRANSITION_INVALID,
                    "Không chuyển được từ " + status + " sang " + next);
        }
        boolean closing = next == ContactStatus.HANDLED || next == ContactStatus.SPAM;
        return new ContactRequest(id, fullName, email, phone, organization, businessType, message, consentAt, next,
                version, createdAt, closing ? by : handledBy, closing ? now : handledAt, null);
    }

    /** Xóa họ tên, email, điện thoại, lời nhắn; idempotent (đã ẩn danh thì trả chính nó). */
    public ContactRequest anonymize(Instant now) {
        if (isAnonymized()) {
            return this;
        }
        return new ContactRequest(id, null, null, null, organization, businessType, null, consentAt, status, version,
                createdAt, handledBy, handledAt, now);
    }

    /** Đã quá hạn lưu giữ (kể từ created_at) và chưa ẩn danh. */
    public boolean isDueForAnonymization(Instant cutoff) {
        return !isAnonymized() && createdAt.isBefore(cutoff);
    }

    public void requireVersion(long expected) {
        if (expected != version) {
            throw new CmsException(CmsErrorCode.CONTACT_REQUEST_VERSION_CONFLICT,
                    "Yêu cầu đã bị người khác sửa, hãy tải lại");
        }
    }

    private static String required(String value, int max, String label, boolean multiline) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw invalid(label + " không được rỗng, tối đa " + max + " ký tự");
        }
        if (TextRules.hasControlChars(value, multiline)) {
            throw invalid(label + " chứa ký tự điều khiển không hợp lệ");
        }
        return value.trim();
    }

    private static CmsException invalid(String message) {
        return new CmsException(CmsErrorCode.CONTACT_REQUEST_INVALID, message);
    }

    public UUID id() { return id; }
    public String fullName() { return fullName; }
    public String email() { return email; }
    public String phone() { return phone; }
    public String organization() { return organization; }
    public BusinessType businessType() { return businessType; }
    public String message() { return message; }
    public Instant consentAt() { return consentAt; }
    public ContactStatus status() { return status; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public UUID handledBy() { return handledBy; }
    public Instant handledAt() { return handledAt; }
    public Instant anonymizedAt() { return anonymizedAt; }

    @Override
    public String toString() {
        return "ContactRequest[id=" + id + ", status=" + status + ", anonymized=" + isAnonymized() + "]";
    }
}
