package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.DuplicateReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceNotFoundException;
import lms.catalog.catalog.domain.ReferenceRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Sửa tên/mô tả (và cha với thể loại) của một mục danh mục; không đổi trạng thái. */
public class UpdateReferenceUseCase {

    private final ReferenceRepository repository;

    public UpdateReferenceUseCase(ReferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ReferenceResponse execute(ReferenceKind kind, UUID id, String name, String description, UUID parentId) {
        Reference current = repository.findById(kind, id).orElseThrow(() -> new ReferenceNotFoundException(kind, id));
        Reference updated = current.update(name, description, parentId);
        if (repository.existsByNameKey(kind, updated.nameKey(), id)) {
            throw new DuplicateReferenceException(kind, updated.name());
        }
        ReferenceRules.checkParent(repository, id, parentId, current.parentId());
        Reference saved = repository.save(updated);
        return GetReferenceUseCase.toResponse(repository, saved);
    }
}
