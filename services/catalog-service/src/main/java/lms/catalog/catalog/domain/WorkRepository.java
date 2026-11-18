package lms.catalog.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu trữ tác phẩm (kèm tác giả chuẩn work_authors). Mọi truy vấn thuộc tenant hiện tại. */
public interface WorkRepository {

    /** Ghi mới hoặc cập nhật (kèm thay work_authors), flush để ấn bản ghi sau thấy khóa ngoại. */
    Work save(Work work);

    /**
     * Ghi mới cả loạt tác phẩm (kèm tác giả chuẩn) bằng persist theo batch, không kiểm trùng, flush để ấn bản ghi sau
     * thấy khóa ngoại (nhập Excel, ADR-0026 mục 3.8). Ném {@link TitleBatchConflictException} khi vi phạm ràng buộc.
     */
    default void insertAll(Collection<Work> works) {
        throw new UnsupportedOperationException("insertAll");
    }

    Optional<Work> findById(UUID id);

    /** Tác phẩm theo id (kèm tác giả chuẩn): một truy vấn tác phẩm và một truy vấn tác giả. */
    List<Work> findByIds(Collection<UUID> ids);

    /** Xóa tác phẩm (work_authors xóa theo). Người gọi bảo đảm không còn ấn bản nào trỏ tới. */
    void delete(UUID id);

    /** Id tác phẩm thuộc một bộ sách. */
    List<UUID> findIdsBySeries(UUID seriesId);

    /** Tìm có phân trang (page từ 0), sắp theo tên rồi id. */
    Page search(Criteria criteria, int page, int size);

    record Criteria(String keyword, UUID seriesId, UUID categoryId, UUID authorId) {
    }

    record Page(List<Work> items, long total) {
    }
}
