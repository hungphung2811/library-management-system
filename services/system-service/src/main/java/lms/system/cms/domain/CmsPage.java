package lms.system.cms.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Trang nội dung cấp nền tảng. Bản làm việc ({@code title}, {@code bodyMarkdown}) và bản đã xuất bản
 * ({@code published*}) cùng một đối tượng; công khai chỉ đọc bản đã xuất bản. Bất biến: mọi thay đổi trả đối tượng mới.
 */
public final class CmsPage {

    public static final int SLUG_MAX = 80;
    public static final int TITLE_MAX = 200;
    public static final int BODY_MAX = 50_000;
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]+$");

    private final UUID id;
    private final String slug;
    private final String title;
    private final String bodyMarkdown;
    private final String publishedTitle;
    private final String publishedBodyMarkdown;
    private final Instant publishedAt;
    private final int sortOrder;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final UUID updatedBy;

    public CmsPage(UUID id, String slug, String title, String bodyMarkdown, String publishedTitle,
                   String publishedBodyMarkdown, Instant publishedAt, int sortOrder, long version, Instant createdAt,
                   Instant updatedAt, UUID updatedBy) {
        this.id = Objects.requireNonNull(id);
        this.slug = slug;
        this.title = title;
        this.bodyMarkdown = bodyMarkdown;
        this.publishedTitle = publishedTitle;
        this.publishedBodyMarkdown = publishedBodyMarkdown;
        this.publishedAt = publishedAt;
        this.sortOrder = sortOrder;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    /** Tạo trang nháp mới (version 0, chưa xuất bản). */
    public static CmsPage create(UUID id, String slug, String title, String bodyMarkdown, int sortOrder, UUID by,
                                 Instant now) {
        String cleanSlug = slug == null ? null : slug.trim();
        validateSlug(cleanSlug);
        validateContent(title, bodyMarkdown);
        return new CmsPage(id, cleanSlug, title.trim(), bodyMarkdown, null, null, null, sortOrder, 0, now, now, by);
    }

    /** Sửa bản làm việc (slug không đổi để không gãy liên kết); không đụng bản đã xuất bản. */
    public CmsPage edit(String newTitle, String newBody, int newSortOrder, UUID by, Instant now) {
        validateContent(newTitle, newBody);
        return new CmsPage(id, slug, newTitle.trim(), newBody, publishedTitle, publishedBodyMarkdown, publishedAt,
                newSortOrder, version, createdAt, now, by);
    }

    /** Chép bản làm việc sang bản xuất bản. */
    public CmsPage publish(UUID by, Instant now) {
        return new CmsPage(id, slug, title, bodyMarkdown, title, bodyMarkdown, now, sortOrder, version, createdAt,
                now, by);
    }

    /** Gỡ bản xuất bản (bản làm việc giữ nguyên). */
    public CmsPage unpublish(UUID by, Instant now) {
        return new CmsPage(id, slug, title, bodyMarkdown, null, null, null, sortOrder, version, createdAt, now, by);
    }

    /** Khóa lạc quan: phiên bản người sửa nhìn thấy phải bằng phiên bản hiện tại. */
    public void requireVersion(long expected) {
        if (expected != version) {
            throw new CmsException(CmsErrorCode.CMS_VERSION_CONFLICT, "Trang đã bị người khác sửa, hãy tải lại");
        }
    }

    public CmsPageStatus status() {
        if (publishedAt == null) {
            return CmsPageStatus.DRAFT;
        }
        return title.equals(publishedTitle) && bodyMarkdown.equals(publishedBodyMarkdown)
                ? CmsPageStatus.PUBLISHED : CmsPageStatus.PUBLISHED_WITH_CHANGES;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public static void validateSlug(String slug) {
        if (slug == null || slug.isEmpty() || slug.length() > SLUG_MAX || !SLUG.matcher(slug).matches()) {
            throw new CmsException(CmsErrorCode.CMS_SLUG_INVALID,
                    "Slug chỉ gồm chữ thường, số, dấu gạch ngang, tối đa " + SLUG_MAX + " ký tự");
        }
    }

    private static void validateContent(String title, String body) {
        if (title == null || title.isBlank() || title.trim().length() > TITLE_MAX) {
            throw new CmsException(CmsErrorCode.CMS_PAGE_INVALID,
                    "Tiêu đề không được rỗng, tối đa " + TITLE_MAX + " ký tự");
        }
        if (body == null) {
            throw new CmsException(CmsErrorCode.CMS_PAGE_INVALID, "Nội dung không được null");
        }
        if (TextRules.hasControlChars(title, false) || TextRules.hasControlChars(body, true)) {
            throw new CmsException(CmsErrorCode.CMS_PAGE_INVALID, "Nội dung chứa ký tự điều khiển không hợp lệ");
        }
        if (body.length() > BODY_MAX) {
            throw new CmsException(CmsErrorCode.CMS_CONTENT_TOO_LONG, "Nội dung tối đa " + BODY_MAX + " ký tự");
        }
    }

    public UUID id() { return id; }
    public String slug() { return slug; }
    public String title() { return title; }
    public String bodyMarkdown() { return bodyMarkdown; }
    public String publishedTitle() { return publishedTitle; }
    public String publishedBodyMarkdown() { return publishedBodyMarkdown; }
    public Instant publishedAt() { return publishedAt; }
    public int sortOrder() { return sortOrder; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public UUID updatedBy() { return updatedBy; }
}
