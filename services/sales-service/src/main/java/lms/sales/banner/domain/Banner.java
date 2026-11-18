package lms.sales.banner.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Banner trang chủ cửa hàng (ADR-0019 2.5). Domain thuần: chuẩn hóa/kiểm đầu vào khi {@link #create} và
 * {@link #replaceWith}, hiệu lực theo thời gian {@link #isLiveAt}. Hiệu lực: {@code active AND (startsAt null hoặc
 * startsAt <= now) AND (endsAt null hoặc now < endsAt)}.
 */
public final class Banner {

    public static final int MAX_TITLE = 100;
    public static final int MAX_SUBTITLE = 200;

    private final UUID id;
    private final UUID tenantId;
    private final String title;
    private final String subtitle;
    private final String linkPath;
    private final int sortOrder;
    private final boolean active;
    private final Instant startsAt;
    private final Instant endsAt;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final UUID createdBy;
    private final UUID updatedBy;

    private Banner(UUID id, UUID tenantId, String title, String subtitle, String linkPath, int sortOrder,
                   boolean active, Instant startsAt, Instant endsAt, long version, Instant createdAt,
                   Instant updatedAt, UUID createdBy, UUID updatedBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.title = title;
        this.subtitle = subtitle;
        this.linkPath = linkPath;
        this.sortOrder = sortOrder;
        this.active = active;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
    }

    /** Tạo mới từ đầu vào thô; sortOrder null thành 0, active null thành true. Sai quy tắc: INVALID_BANNER. */
    public static Banner create(UUID id, UUID tenantId, String title, String subtitle, String linkPath,
                                Integer sortOrder, Boolean active, Instant startsAt, Instant endsAt, UUID actorId,
                                Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(now, "now");
        return new Banner(id, tenantId, BannerText.required(title, MAX_TITLE, "title"),
                BannerText.optional(subtitle, MAX_SUBTITLE, "subtitle"), BannerLink.parse(linkPath),
                checkSortOrder(sortOrder), active == null || active, checkWindow(startsAt, endsAt), endsAt, 0, now,
                now, actorId, actorId);
    }

    /** Nạp lại từ lưu trữ (không kiểm lại đầu vào). */
    public static Banner restore(UUID id, UUID tenantId, String title, String subtitle, String linkPath,
                                 int sortOrder, boolean active, Instant startsAt, Instant endsAt, long version,
                                 Instant createdAt, Instant updatedAt, UUID createdBy, UUID updatedBy) {
        return new Banner(id, tenantId, title, subtitle, linkPath, sortOrder, active, startsAt, endsAt, version,
                createdAt, updatedAt, createdBy, updatedBy);
    }

    /** PUT thay toàn bộ trường (trường vắng là null/mặc định); giữ id, tenant, version, createdAt/By. */
    public Banner replaceWith(String newTitle, String newSubtitle, String newLinkPath, Integer newSortOrder,
                              Boolean newActive, Instant newStartsAt, Instant newEndsAt, UUID actorId, Instant now) {
        return new Banner(id, tenantId, BannerText.required(newTitle, MAX_TITLE, "title"),
                BannerText.optional(newSubtitle, MAX_SUBTITLE, "subtitle"), BannerLink.parse(newLinkPath),
                checkSortOrder(newSortOrder), newActive == null || newActive, checkWindow(newStartsAt, newEndsAt),
                newEndsAt, version, createdAt, now, createdBy, actorId);
    }

    private static int checkSortOrder(Integer sortOrder) {
        int v = sortOrder == null ? 0 : sortOrder;
        if (v < 0 || v > BannerLimits.MAX_SORT_ORDER) {
            throw BannerText.invalid("sortOrder");
        }
        return v;
    }

    private static Instant checkWindow(Instant startsAt, Instant endsAt) {
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw BannerText.invalid("endsAt");
        }
        return startsAt;
    }

    public boolean isLiveAt(Instant now) {
        return active && (startsAt == null || !startsAt.isAfter(now)) && (endsAt == null || now.isBefore(endsAt));
    }

    public BannerStatus statusAt(Instant now) {
        if (!active) {
            return BannerStatus.INACTIVE;
        }
        if (startsAt != null && now.isBefore(startsAt)) {
            return BannerStatus.SCHEDULED;
        }
        if (endsAt != null && !now.isBefore(endsAt)) {
            return BannerStatus.EXPIRED;
        }
        return BannerStatus.LIVE;
    }

    public static SalesException notFound() {
        return new SalesException(ErrorCode.BANNER_NOT_FOUND, "Không tìm thấy banner");
    }

    public static SalesException versionConflict() {
        return new SalesException(ErrorCode.BANNER_VERSION_CONFLICT, "Banner đã bị thay đổi bởi thao tác khác");
    }

    public static SalesException limitExceeded() {
        return new SalesException(ErrorCode.BANNER_LIMIT_EXCEEDED,
                "Mỗi cửa hàng tối đa " + BannerLimits.MAX_PER_TENANT + " banner");
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String title() { return title; }
    public String subtitle() { return subtitle; }
    public String linkPath() { return linkPath; }
    public int sortOrder() { return sortOrder; }
    public boolean active() { return active; }
    public Instant startsAt() { return startsAt; }
    public Instant endsAt() { return endsAt; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public UUID createdBy() { return createdBy; }
    public UUID updatedBy() { return updatedBy; }

    @Override
    public String toString() {
        return "Banner[id=" + id + ", sortOrder=" + sortOrder + ", active=" + active + "]";
    }
}
