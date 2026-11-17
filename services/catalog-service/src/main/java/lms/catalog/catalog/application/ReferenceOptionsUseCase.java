package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** Danh sách rút gọn (id, tên) các mục đang active cho ô chọn; tối đa 200, lọc theo từ khóa tên. */
public class ReferenceOptionsUseCase {

    public static final int MAX_OPTIONS = 200;

    private final ReferenceRepository repository;

    public ReferenceOptionsUseCase(ReferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ReferenceOption> execute(ReferenceKind kind, String keyword) {
        return repository.options(kind, keyword, MAX_OPTIONS).stream()
                .map((Reference r) -> new ReferenceOption(r.id(), r.name(), r.parentId())).toList();
    }
}
