package lms.tenant.export;

/**
 * Bảng hoặc cột bị loại khỏi gói xuất có chủ đích (ADR-0031 D2), ghi vào {@code _meta.json} cùng lý do.
 * {@code target} là tên bảng ({@code "refresh_tokens"}) hoặc {@code "bảng.cột"} ({@code "notification_deliveries.params"}).
 */
public record ExportExclusion(String target, String reason) {

    public ExportExclusion {
        if (target == null || target.isBlank()) throw new IllegalArgumentException("target bắt buộc");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason bắt buộc");
    }
}
