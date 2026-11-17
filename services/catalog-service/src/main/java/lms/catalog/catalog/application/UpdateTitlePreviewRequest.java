package lms.catalog.catalog.application;

import jakarta.validation.constraints.Size;

/**
 * Body của PUT /internal/books/{id}/preview: thay cả hai trường (null hoặc rỗng = xóa). Giới hạn 500/2000 ký tự
 * kiểm ở domain theo code point; ở đây chỉ chặn thô thân quá lớn ({@code @Size} đếm UTF-16 nên không đặt 500/2000).
 */
public record UpdateTitlePreviewRequest(
        @Size(max = 2000) String description,
        @Size(max = 8000) String previewText) {
}
