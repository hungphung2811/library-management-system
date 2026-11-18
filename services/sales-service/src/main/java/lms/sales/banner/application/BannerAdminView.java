package lms.sales.banner.application;

import java.time.Instant;
import java.util.UUID;
import lms.sales.banner.domain.Banner;
import lms.sales.banner.domain.BannerStatus;

/** Hình chiếu quản trị: mọi trường kèm trạng thái do server tính. */
public record BannerAdminView(UUID id, String title, String subtitle, String linkPath, int sortOrder, boolean active,
                              Instant startsAt, Instant endsAt, BannerStatus status, long version,
                              Instant createdAt, Instant updatedAt) {

    public static BannerAdminView from(Banner b, Instant now) {
        return new BannerAdminView(b.id(), b.title(), b.subtitle(), b.linkPath(), b.sortOrder(), b.active(),
                b.startsAt(), b.endsAt(), b.statusAt(now), b.version(), b.createdAt(), b.updatedAt());
    }
}
