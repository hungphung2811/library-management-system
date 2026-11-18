package lms.customer.banner.client;

import java.util.List;
import lms.customer.banner.dto.BannerDtos.BannerView;

/** Cổng sang sales {@code GET /internal/sales/storefront/banners}, luôn bằng token dịch vụ storefront của tenant. */
public interface BannerClient {

    /** Tối đa 10 banner đang hiệu lực, đã sắp theo sortOrder; rỗng nếu không có. */
    List<BannerView> list(String serviceToken);
}
