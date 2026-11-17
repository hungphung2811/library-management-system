package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import java.util.UUID;

/** Mô tả và đoạn đọc thử của một đầu sách (null nếu chưa nhập). Không ISBN, giá bìa, tenantId. */
public record TitlePreviewResponse(UUID id, String name, String description, String previewText) {

    public static TitlePreviewResponse from(Title title) {
        return new TitlePreviewResponse(title.id(), title.name(), title.description(), title.previewText());
    }
}
