package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceNotFoundException;
import lms.catalog.catalog.domain.ReferenceRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

public class GetReferenceUseCase {

    private final ReferenceRepository repository;

    public GetReferenceUseCase(ReferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ReferenceResponse execute(ReferenceKind kind, UUID id) {
        Reference r = repository.findById(kind, id).orElseThrow(() -> new ReferenceNotFoundException(kind, id));
        return toResponse(repository, r);
    }

    static ReferenceResponse toResponse(ReferenceRepository repository, Reference r) {
        return toResponses(repository, List.of(r)).get(0);
    }

    /** Gắn tên cha (một truy vấn cho cả danh sách) cho thể loại. */
    static List<ReferenceResponse> toResponses(ReferenceRepository repository, List<Reference> items) {
        List<UUID> parentIds = items.stream().map(Reference::parentId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        Map<UUID, String> names = parentIds.isEmpty() ? Map.of()
                : repository.findByIds(ReferenceKind.CATEGORY, parentIds).stream()
                        .collect(Collectors.toMap(Reference::id, Reference::name));
        return items.stream().map(r -> ReferenceResponse.from(r, r.parentId() == null ? null : names.get(r.parentId())))
                .toList();
    }
}
