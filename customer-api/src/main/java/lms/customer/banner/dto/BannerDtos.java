package lms.customer.banner.dto;

import java.util.UUID;

/** DTO công khai của banner (ADR-0019 2.5, 2.6): ĐÚNG các trường dưới đây, chỉ văn bản thuần. Test so tập tên trường. */
public final class BannerDtos {

    private BannerDtos() {
    }

    /**
     * @param title    văn bản thuần
     * @param subtitle văn bản thuần hoặc null
     * @param linkPath null hoặc đường dẫn tương đối bắt đầu bằng đúng một "/"
     */
    public record BannerView(UUID id, String title, String subtitle, String linkPath, int sortOrder) {
    }
}
