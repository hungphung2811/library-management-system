package lms.catalog.catalog.application;

import java.util.UUID;

/** Dạng rút gọn cho ô chọn (chỉ mục đang active). parentId chỉ có ở thể loại. */
public record ReferenceOption(UUID id, String name, UUID parentId) {
}
