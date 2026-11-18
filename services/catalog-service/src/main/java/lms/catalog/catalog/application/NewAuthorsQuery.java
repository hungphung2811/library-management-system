package lms.catalog.catalog.application;

import java.time.Instant;
import java.util.List;

/** Port đọc: tên tác giả (hiển thị) có đầu sách tạo từ {@code since}, mới nhất trước, tối đa {@code limit}. */
public interface NewAuthorsQuery {

    /**
     * Tác giả distinct (khớp không phân biệt hoa thường và khoảng trắng đầu/cuối), trả tên hiển thị của ấn bản mới nhất,
     * sắp theo thời điểm tạo mới nhất giảm dần. Chỉ tenant hiện tại.
     */
    List<String> findAuthorsWithTitlesSince(Instant since, int limit);
}
