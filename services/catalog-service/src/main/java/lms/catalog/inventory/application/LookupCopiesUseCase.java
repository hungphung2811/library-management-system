package lms.catalog.inventory.application;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyLookupItem;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tra nhiều bản sao theo id trong một lần (circulation dùng cho báo cáo quá hạn). Id không tồn tại hoặc thuộc
 * tenant khác (repository lọc tenant) thì vắng trong kết quả, không báo lỗi. Thứ tự theo thứ tự id yêu cầu, id trùng
 * chỉ trả một lần. Tối đa {@value LookupCopiesRequest#MAX_IDS} id.
 */
public class LookupCopiesUseCase {

    private final BookCopyRepository repository;

    public LookupCopiesUseCase(BookCopyRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<CopyLookupView> execute(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > LookupCopiesRequest.MAX_IDS) {
            throw new IllegalArgumentException("Tối đa " + LookupCopiesRequest.MAX_IDS + " id mỗi lần tra");
        }
        LinkedHashSet<UUID> distinct = new LinkedHashSet<>();
        for (UUID id : ids) {
            distinct.add(Objects.requireNonNull(id, "id bản sao null"));
        }
        Map<UUID, CopyLookupItem> found = new LinkedHashMap<>();
        for (CopyLookupItem item : repository.findLookupByIds(distinct)) {
            found.put(item.id(), item);
        }
        return distinct.stream().map(found::get).filter(Objects::nonNull).map(CopyLookupView::from).toList();
    }
}
