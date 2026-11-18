package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.DuplicateReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Tạo một mục danh mục chuẩn (tác giả, nhà xuất bản, thể loại, vị trí kệ); chặn trùng tên theo tenant. */
public class CreateReferenceUseCase {

    private final ReferenceRepository repository;

    public CreateReferenceUseCase(ReferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ReferenceResponse execute(ReferenceKind kind, String name, String description, UUID parentId) {
        return execute(kind, name, description, parentId, null);
    }

    @Transactional
    public ReferenceResponse execute(ReferenceKind kind, String name, String description, UUID parentId,
                                     ReferenceExtras extras) {
        Reference reference = Reference.create(kind, name, description, parentId, extras);
        if (repository.existsByNameKey(kind, reference.nameKey(), null)) {
            throw new DuplicateReferenceException(kind, reference.name());
        }
        ReferenceRules.checkParent(repository, null, parentId, null);
        Reference saved = repository.save(reference);
        return GetReferenceUseCase.toResponse(repository, saved);
    }
}
