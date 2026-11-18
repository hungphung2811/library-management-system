package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.DuplicateReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceNotFoundException;
import lms.catalog.catalog.domain.ReferenceRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sửa tên/mô tả (và cha với thể loại) của một mục danh mục; không đổi trạng thái. Bản có tham số {@code extras} là PUT
 * thay toàn bộ thông tin bổ sung (null = xóa hết); bản cũ không có {@code extras} giữ nguyên thông tin bổ sung.
 */
public class UpdateReferenceUseCase {

    private final ReferenceRepository repository;

    public UpdateReferenceUseCase(ReferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ReferenceResponse execute(ReferenceKind kind, UUID id, String name, String description, UUID parentId) {
        Reference current = repository.findById(kind, id).orElseThrow(() -> new ReferenceNotFoundException(kind, id));
        return save(kind, id, current, current.update(name, description, parentId), parentId);
    }

    @Transactional
    public ReferenceResponse execute(ReferenceKind kind, UUID id, String name, String description, UUID parentId,
                                     ReferenceExtras extras) {
        Reference current = repository.findById(kind, id).orElseThrow(() -> new ReferenceNotFoundException(kind, id));
        return save(kind, id, current, current.update(name, description, parentId, extras), parentId);
    }

    private ReferenceResponse save(ReferenceKind kind, UUID id, Reference current, Reference updated, UUID parentId) {
        if (repository.existsByNameKey(kind, updated.nameKey(), id)) {
            throw new DuplicateReferenceException(kind, updated.name());
        }
        ReferenceRules.checkParent(repository, id, parentId, current.parentId());
        Reference saved = repository.save(updated);
        return GetReferenceUseCase.toResponse(repository, saved);
    }
}
