package lms.customer.author.client;

import java.util.List;
import lms.customer.author.dto.AuthorDtos.TopSoldItem;

/** Cổng sang sales {@code GET /internal/sales/storefront/top-sold} bằng TOKEN DỊCH VỤ của tenant. */
public interface TopSoldClient {

    /** Đầu sách bán chạy trong {@code days} ngày, số lượng giảm dần, tối đa {@code limit} dòng. */
    List<TopSoldItem> topSold(String serviceToken, int days, int limit);
}
