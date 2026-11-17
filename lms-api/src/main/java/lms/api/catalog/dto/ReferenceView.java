package lms.api.catalog.dto;

import java.util.UUID;

/** Mục danh mục chuẩn (tác giả, nhà xuất bản, thể loại) trả cho FE; parentId/parentName chỉ có ở thể loại. */
public record ReferenceView(UUID id, String name, String description, UUID parentId, String parentName,
                            boolean active, UUID tenantId) {
}
