package lms.api.catalog.dto;

import java.util.UUID;

/** Tác giả chuẩn của đầu sách. */
public record AuthorRefView(UUID id, String name) {
}
