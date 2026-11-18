package lms.sales.banner.application;

import java.util.UUID;
import lms.sales.banner.domain.Banner;

/** Hình chiếu công khai: đúng 5 trường, không lộ active/thời gian/phiên bản/người tạo. */
public record PublicBannerView(UUID id, String title, String subtitle, String linkPath, int sortOrder) {

    public static PublicBannerView from(Banner b) {
        return new PublicBannerView(b.id(), b.title(), b.subtitle(), b.linkPath(), b.sortOrder());
    }
}
