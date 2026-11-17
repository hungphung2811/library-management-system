package lms.api.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO quản trị CMS cho FE (/api/cms/**), ánh xạ 1-1 tới /internal/cms/** của system-service (ADR-0011 mục 3.4).
 * Đã khớp hợp đồng B-C3 (CmsResponses/CmsRequests của system-service).
 */
public final class CmsDtos {

    private CmsDtos() {
    }

    /** Một dòng trong danh sách trang (không có nội dung). status: DRAFT, PUBLISHED, PUBLISHED_WITH_CHANGES. */
    public record CmsPageSummary(UUID id, String slug, String title, String status, int sortOrder,
                                 Instant publishedAt, long version, Instant updatedAt) {
    }

    /** Trang CMS cho admin: có cả bản làm việc và bản đã xuất bản. status: DRAFT, PUBLISHED, PUBLISHED_WITH_CHANGES. */
    public record CmsPageView(UUID id, String slug, String title, String bodyMarkdown, String status,
                              String publishedTitle, String publishedBodyMarkdown, Instant publishedAt,
                              int sortOrder, long version, Instant createdAt, Instant updatedAt, UUID updatedBy) {
    }

    /** Xuất bản đúng phiên bản đã xem; lệch thì 409 CMS_VERSION_CONFLICT. */
    public record PublishCmsPageRequest(@NotNull Long version) {
    }

    public record CreateCmsPageRequest(@NotBlank @Size(max = 80) @Pattern(regexp = "^[a-z0-9-]+$") String slug,
                                       @NotBlank @Size(max = 200) String title,
                                       @NotNull @Size(max = 50000) String bodyMarkdown, Integer sortOrder) {
    }

    /** slug không đổi được; version để khóa lạc quan (409 CMS_VERSION_CONFLICT). */
    public record UpdateCmsPageRequest(@NotBlank @Size(max = 200) String title,
                                       @NotNull @Size(max = 50000) String bodyMarkdown, @NotNull Integer sortOrder,
                                       @NotNull Long version) {
    }

    /**
     * Yêu cầu liên hệ cho platform admin. Sau khi ẩn danh, fullName/email/phone/message là null và anonymizedAt có giá trị.
     * status: NEW, IN_PROGRESS, HANDLED, SPAM; businessType: LIBRARY, BOOKSTORE, OTHER.
     */
    public record ContactRequestView(UUID id, String fullName, String email, String phone, String organization,
                                     String businessType, String message, Instant consentAt, String status,
                                     long version, Instant createdAt, UUID handledBy, Instant handledAt,
                                     Instant anonymizedAt) {
        /** Không in dữ liệu cá nhân (email, điện thoại, họ tên, lời nhắn) vào log. */
        @Override
        public String toString() {
            return "ContactRequestView[id=" + id + ", status=" + status + "]";
        }
    }

    /** Dòng danh sách yêu cầu liên hệ (không có phone, message). Sau khi ẩn danh fullName/email là null. */
    public record ContactRequestSummary(UUID id, String fullName, String email, String organization,
                                        String businessType, String status, Instant createdAt, Instant anonymizedAt) {
        /** Không in dữ liệu cá nhân vào log. */
        @Override
        public String toString() {
            return "ContactRequestSummary[id=" + id + ", status=" + status + "]";
        }
    }

    public record ContactRequestPageView(List<ContactRequestSummary> items, long total, int page, int size) {
    }

    /** Query GET /api/cms/contact-requests, đều tùy chọn, chuyển nguyên xuống service (from/to là ISO-8601 instant; from gồm, to không gồm). */
    public record ContactRequestSearchQuery(String status, String from, String to, Integer page, Integer size) {
    }

    public record UpdateContactRequestStatusRequest(@NotBlank @Size(max = 20) String status, @NotNull Long version) {
    }
}
