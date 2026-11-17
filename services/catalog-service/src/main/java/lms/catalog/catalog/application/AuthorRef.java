package lms.catalog.catalog.application;

import java.util.UUID;

/** Tác giả chuẩn của đầu sách (id và tên hiển thị). */
public record AuthorRef(UUID id, String name) {
}
