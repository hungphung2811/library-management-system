package lms.system.cms.domain;

/** Mã lỗi nghiệp vụ của context cms (ADR-0011 mục 3.4). Ánh xạ sang HTTP do tầng web đảm nhiệm. */
public enum CmsErrorCode {
    CMS_PAGE_NOT_FOUND,
    CMS_SLUG_DUPLICATED,
    CMS_VERSION_CONFLICT,
    CMS_SLUG_INVALID,
    CMS_CONTENT_TOO_LONG,
    /** Tiêu đề rỗng/quá dài hoặc dữ liệu trang khác không hợp lệ. */
    CMS_PAGE_INVALID,
    CONTACT_REQUEST_NOT_FOUND,
    /** Yêu cầu đã ẩn danh nên không sửa được nữa. */
    CONTACT_REQUEST_ANONYMIZED,
    CONTACT_REQUEST_VERSION_CONFLICT,
    /** Chuyển trạng thái không nằm trong các bước cho phép. */
    CONTACT_STATUS_TRANSITION_INVALID,
    CONSENT_REQUIRED,
    /** Họ tên, email, tổ chức, lời nhắn rỗng/quá dài hoặc sai định dạng. */
    CONTACT_REQUEST_INVALID
}
