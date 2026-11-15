package lms.circulation.borrowing.domain;

import java.util.List;

/** Quy tắc mượn của tenant hiện tại (lọc theo tenant ở adapter và RLS). */
public interface BorrowRuleRepository {

    /** Quy tắc của tenant hiện tại; không có tenant thì rỗng. */
    List<BorrowRule> findAll();

    /** Thêm quy tắc cho tenant hiện tại nếu loại độc giả đó chưa có (giữ nguyên quy tắc đã sửa). @return true nếu đã thêm. */
    boolean addIfAbsent(BorrowRule rule);
}
