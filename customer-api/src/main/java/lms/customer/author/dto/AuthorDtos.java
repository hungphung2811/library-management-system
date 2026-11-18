package lms.customer.author.dto;

import java.util.UUID;

/**
 * DTO của "tác giả nổi bật". {@link AuthorView} là bản công khai: ĐÚNG một trường {@code name}, không số lượng bán,
 * doanh thu hay id. {@link TopSoldItem} chỉ dùng nội bộ giữa client và thuật toán, không bao giờ trả ra ngoài.
 */
public final class AuthorDtos {

    private AuthorDtos() {
    }

    public record AuthorView(String name) {
    }

    /** Một dòng bán chạy của sales: số lượng đã bán của đầu sách trong khoảng ngày. */
    public record TopSoldItem(UUID titleId, long quantity) {
    }
}
