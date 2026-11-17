package lms.system.cms.domain;

/** Trạng thái suy ra từ hai bản của trang (ADR-0011 mục 2.11). */
public enum CmsPageStatus {
    /** Chưa xuất bản. */
    DRAFT,
    /** Bản làm việc trùng bản đã xuất bản. */
    PUBLISHED,
    /** Đã xuất bản nhưng bản làm việc có thay đổi chưa xuất bản. */
    PUBLISHED_WITH_CHANGES
}
