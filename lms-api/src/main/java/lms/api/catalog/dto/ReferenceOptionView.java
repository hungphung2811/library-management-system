package lms.api.catalog.dto;

import java.util.UUID;

/** Dạng rút gọn cho ô chọn (chỉ mục đang active); parentId chỉ có ở thể loại. */
public record ReferenceOptionView(UUID id, String name, UUID parentId) {
}
