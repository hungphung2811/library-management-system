package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceNotFoundException;
import lms.catalog.catalog.domain.ReferenceRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Bật/ngừng sử dụng một mục (idempotent). Ngừng dùng thay cho xóa cứng: các tham chiếu cũ vẫn hợp lệ. */
public class SetReferenceStatusUseCase {

    private final ReferenceRepository repository;

    public SetReferenceStatusUseCase(ReferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ReferenceResponse execute(ReferenceKind kind, UUID id, boolean active) {
        Reference current = repository.findById(kind, id).orElseThrow(() -> new ReferenceNotFoundException(kind, id));
        Reference saved = current.active() == active ? current : repository.save(current.withActive(active));
        return GetReferenceUseCase.toResponse(repository, saved);
    }
}
