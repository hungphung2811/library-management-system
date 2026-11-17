package lms.system.cms.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

/** Dòng chiếu không có nội dung cho danh sách quản trị; cờ so sánh bản nháp với bản xuất bản tính trong DB. */
public record AdminRow(UUID id, String slug, String title, Instant publishedAt, int sortOrder, Long version,
                       Instant updatedAt, boolean unchangedSincePublish) {
}
