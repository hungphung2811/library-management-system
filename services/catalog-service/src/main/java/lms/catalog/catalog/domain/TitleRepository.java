package lms.catalog.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TitleRepository {

    Title save(Title title);

    Optional<Title> findById(UUID id);

    boolean existsByIsbn(String isbn);

    /** ISBN đã thuộc về đầu sách khác (loại trừ chính đầu sách có id này). */
    boolean existsByIsbnAndIdNot(String isbn, UUID id);

    /** Tìm theo tên hoặc tác giả, không phân biệt hoa thường; keyword rỗng/null thì lấy tất cả. */
    List<Title> search(String keyword);

    /** Như search nhưng tối đa {@code limit} đầu sách (theo tên). */
    default List<Title> searchLimited(String keyword, int limit) {
        return search(keyword).stream().limit(limit).toList();
    }

    List<Title> findByIds(Collection<UUID> ids);
}
