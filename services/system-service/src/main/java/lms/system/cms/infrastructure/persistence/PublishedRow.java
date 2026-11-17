package lms.system.cms.infrastructure.persistence;

/** Dòng chiếu 3 cột cho danh sách công khai (public để Hibernate dựng bằng constructor expression). */
public record PublishedRow(String slug, String title, int sortOrder) {
}
