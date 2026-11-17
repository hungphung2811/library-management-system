package lms.circulation.reporting.domain;

import java.util.UUID;

/** Đầu sách và số bản sao đã mượn trong khoảng. */
public record TopTitle(UUID titleId, long count) {
}
