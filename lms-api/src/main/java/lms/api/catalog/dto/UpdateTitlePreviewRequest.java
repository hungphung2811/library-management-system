package lms.api.catalog.dto;

/**
 * Thay cả hai trường (PUT); null hoặc rỗng = xóa. Không ràng buộc độ dài ở đây (500/2000 do catalog quyết,
 * vượt thì catalog trả 400 TITLE_PREVIEW_TOO_LONG và lms-api chuyển nguyên).
 */
public record UpdateTitlePreviewRequest(String description, String previewText) {
}
