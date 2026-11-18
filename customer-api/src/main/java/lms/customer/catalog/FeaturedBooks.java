package lms.customer.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.customer.author.client.TopSoldClient;
import lms.customer.author.dto.AuthorDtos.TopSoldItem;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.dto.BookDtos.BookSummaryView;

/**
 * Sản phẩm nổi bật cho trang chủ: tối đa {@value #LIMIT} sách bán chạy nhất {@value #DAYS} ngày gần nhất (giữ thứ tự
 * bán chạy của sales), chỉ sách còn hàng. Chưa đủ thì bù bằng sách mới nhất còn hàng (loại trùng) để carousel không
 * trống khi chưa có đơn. Một lời gọi {@code top-sold}, tối đa một lời gọi {@code by-ids}, tối đa một lời gọi danh sách.
 * Lỗi downstream được ném nguyên. Bean tạo trong {@link CatalogClientConfiguration}.
 */
public class FeaturedBooks {

    static final int DAYS = 90;
    static final int LIMIT = 8;
    static final int TOP_SOLD_LIMIT = 100;

    private final CatalogClient catalog;
    private final TopSoldClient sales;

    public FeaturedBooks(CatalogClient catalog, TopSoldClient sales) {
        this.catalog = catalog;
        this.sales = sales;
    }

    public List<BookSummaryView> featured(String serviceToken) {
        List<BookSummaryView> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Set<UUID> topIds = new LinkedHashSet<>();
        for (TopSoldItem item : sales.topSold(serviceToken, DAYS, TOP_SOLD_LIMIT)) {
            if (item != null && item.titleId() != null && topIds.size() < LIMIT) {
                topIds.add(item.titleId());
            }
        }
        if (!topIds.isEmpty()) {
            for (BookSummaryView b : catalog.byIds(serviceToken, List.copyOf(topIds))) {
                if (b.inStock() && out.size() < LIMIT && seen.add(b.bookRef())) {
                    out.add(b);
                }
            }
        }
        if (out.size() < LIMIT) {
            // Xin dư theo số đã chọn để còn đủ sau khi loại trùng (tối đa 16, dưới trần 50 của sales).
            int size = LIMIT + out.size();
            for (BookSummaryView b : catalog.list(serviceToken, null, "newest", true, null, 0, size).items()) {
                if (b.inStock() && out.size() < LIMIT && seen.add(b.bookRef())) {
                    out.add(b);
                }
            }
        }
        return List.copyOf(out);
    }
}
