package lms.circulation.borrowing.domain;

import java.util.List;
import java.util.Optional;

/** Quy tắc mượn của tenant hiện tại (lọc theo tenant ở adapter và RLS). */
public interface BorrowRuleRepository {

    /** Quy tắc của tenant hiện tại; không có tenant thì rỗng. */
    List<BorrowRule> findAll();

    /** Thêm quy tắc cho tenant hiện tại nếu loại độc giả đó chưa có (giữ nguyên quy tắc đã sửa). @return true nếu đã thêm. */
    boolean addIfAbsent(BorrowRule rule);

    /**
     * Quy tắc của loại độc giả trong tenant hiện tại, đọc mỗi lần lập phiếu nên đổi quy định không cần sửa code hay
     * khởi động lại. Không có tenant thì rỗng.
     */
    default Optional<BorrowRule> findByReaderType(ReaderType readerType) {
        return findAll().stream().filter(rule -> rule.readerType() == readerType).findFirst();
    }
}
