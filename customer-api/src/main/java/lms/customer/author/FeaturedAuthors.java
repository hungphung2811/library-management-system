package lms.customer.author;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lms.customer.author.client.AuthorCatalogClient;
import lms.customer.author.client.TopSoldClient;
import lms.customer.author.dto.AuthorDtos.AuthorView;
import lms.customer.author.dto.AuthorDtos.TopSoldItem;

/**
 * Tác giả nổi bật: chỉ các tác giả có ấn bản mới trong {@value #DAYS} ngày, xếp theo số lượng bán (cùng khoảng ngày)
 * giảm dần, lấy {@value #LIMIT}. Hòa (kể cả cùng 0) giữ thứ tự của catalog (mới nhất trước), nên tác giả chưa bán được
 * vẫn được điền vào chỗ trống ở cuối. Số lượng chỉ dùng để xếp, không trả ra ngoài. Lỗi downstream được ném nguyên.
 * Bean tạo trong {@link AuthorClientConfiguration}.
 */
public class FeaturedAuthors {

    static final int DAYS = 90;
    static final int LIMIT = 5;
    static final int NEW_AUTHORS_LIMIT = 200;
    static final int TOP_SOLD_LIMIT = 100;

    private final AuthorCatalogClient catalog;
    private final TopSoldClient sales;

    public FeaturedAuthors(AuthorCatalogClient catalog, TopSoldClient sales) {
        this.catalog = catalog;
        this.sales = sales;
    }

    public List<AuthorView> featured(String serviceToken) {
        List<String> newAuthors = catalog.newAuthors(serviceToken, DAYS, NEW_AUTHORS_LIMIT);
        if (newAuthors.isEmpty()) {
            return List.of();
        }
        List<TopSoldItem> topSold = sales.topSold(serviceToken, DAYS, TOP_SOLD_LIMIT);
        Map<String, Long> qtyByAuthor = new HashMap<>();
        if (!topSold.isEmpty()) {
            Map<UUID, String> authorByTitle = catalog.authorsByTitle(serviceToken,
                    topSold.stream().map(TopSoldItem::titleId).distinct().toList());
            for (TopSoldItem item : topSold) {
                String author = authorByTitle.get(item.titleId());
                if (author != null) {
                    qtyByAuthor.merge(normalize(author), Math.max(0, item.quantity()), Long::sum);
                }
            }
        }
        // Gộp trùng tên (khác hoa thường) trong newAuthors, giữ tên và vị trí xuất hiện đầu tiên.
        Map<String, String> display = new LinkedHashMap<>();
        for (String name : newAuthors) {
            if (name != null && !name.isBlank()) {
                display.putIfAbsent(normalize(name), name.trim());
            }
        }
        List<String> keys = new ArrayList<>(display.keySet());
        // List.sort ổn định: hòa giữ thứ tự mới nhất trước.
        keys.sort(Comparator.comparingLong((String k) -> qtyByAuthor.getOrDefault(k, 0L)).reversed());
        return keys.stream().limit(LIMIT).map(k -> new AuthorView(display.get(k))).toList();
    }

    static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
