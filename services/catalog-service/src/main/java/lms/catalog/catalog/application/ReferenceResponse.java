package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Reference;
import java.util.UUID;

/** Một mục danh mục chuẩn. {@code name} của vị trí kệ chính là mã (controller đổi tên thành code); parent* chỉ có ở thể loại. */
public record ReferenceResponse(UUID id, String name, String description, UUID parentId, String parentName,
                                boolean active, UUID tenantId) {

    public static ReferenceResponse from(Reference r, String parentName) {
        return new ReferenceResponse(r.id(), r.name(), r.description(), r.parentId(), parentName, r.active(),
                r.tenantId());
    }
}
