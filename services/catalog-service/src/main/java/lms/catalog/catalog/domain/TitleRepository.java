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

    /** Như search nhưng lọc thêm theo thể loại và/hoặc nhà xuất bản (null = không lọc), khớp đúng id. */
    default List<Title> searchFiltered(String keyword, UUID categoryId, UUID publisherId) {
        return search(keyword).stream()
                .filter(t -> categoryId == null || categoryId.equals(t.categoryId()))
                .filter(t -> publisherId == null || publisherId.equals(t.publisherId()))
                .toList();
    }

    List<Title> findByIds(Collection<UUID> ids);

    /** ISBN (khớp đúng chuỗi) -> id đầu sách của tenant, một truy vấn (chia lô); ISBN không có thì vắng. */
    default java.util.Map<String, UUID> findIdsByIsbns(Collection<String> isbns) {
        java.util.Map<String, UUID> result = new java.util.HashMap<>();
        for (Title t : findByIsbns(isbns)) result.put(t.isbn(), t.id());
        return result;
    }

    /** Ghi mới cả loạt (kèm tác giả chuẩn), không kiểm trùng; ném {@link TitleBatchConflictException} khi trùng khóa. */
    default void insertAll(Collection<Title> titles) {
        throw new UnsupportedOperationException("insertAll");
    }

    /** Ấn bản của các tác phẩm đã cho (sắp theo tên rồi id), một truy vấn (+ một truy vấn tác giả chuẩn). */
    default List<Title> findByWorkIds(Collection<UUID> workIds) {
        throw new UnsupportedOperationException("findByWorkIds");
    }

    /**
     * Ghi gương trường cấp tác phẩm lên MỌI ấn bản của tác phẩm (ADR-0026 mục 4.3): thể loại, tên bộ sách, số tập và
     * thay title_authors theo {@code authorIds}. Bulk update (xóa persistence context). Trong cùng transaction với việc ghi work.
     */
    default void mirrorWorkFields(UUID workId, UUID categoryId, String series, Integer seriesNumber,
                                  List<UUID> authorIds) {
        throw new UnsupportedOperationException("mirrorWorkFields");
    }

    /** Chuyển mọi ấn bản của các tác phẩm nguồn sang tác phẩm đích; trả số ấn bản đã chuyển. */
    default int moveToWork(Collection<UUID> fromWorkIds, UUID toWorkId) {
        throw new UnsupportedOperationException("moveToWork");
    }

    /** Đầu sách của tenant có ISBN thuộc tập đã cho (khớp đúng chuỗi); ISBN không có thì vắng mặt. */
    default List<Title> findByIsbns(Collection<String> isbns) {
        return search(null).stream().filter(t -> t.isbn() != null && isbns.contains(t.isbn())).toList();
    }
}
