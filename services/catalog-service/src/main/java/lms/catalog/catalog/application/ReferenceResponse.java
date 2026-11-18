package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceExtras;
import java.util.UUID;

/** Một mục danh mục chuẩn. {@code name} của vị trí kệ chính là mã (controller đổi tên thành code); parent* chỉ có ở thể loại. */
public record ReferenceResponse(UUID id, String name, String description, UUID parentId, String parentName,
                                boolean active, UUID tenantId, ReferenceExtras extras) {

    public ReferenceResponse(UUID id, String name, String description, UUID parentId, String parentName,
                             boolean active, UUID tenantId) {
        this(id, name, description, parentId, parentName, active, tenantId, ReferenceExtras.EMPTY);
    }

    public static ReferenceResponse from(Reference r, String parentName) {
        return new ReferenceResponse(r.id(), r.name(), r.description(), r.parentId(), parentName, r.active(),
                r.tenantId(), r.extras());
    }
}
