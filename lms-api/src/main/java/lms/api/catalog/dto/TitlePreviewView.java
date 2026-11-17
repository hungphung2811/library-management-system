package lms.api.catalog.dto;

import java.util.UUID;

/** Mô tả ngắn và preview của đầu sách (văn bản thuần); null nếu chưa nhập. Không có ISBN, giá bìa, tenant. */
public record TitlePreviewView(UUID id, String name, String description, String previewText) {
}
