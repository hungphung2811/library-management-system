package lms.sales.tenantdata.domain;

/** Bảng hoặc cột bị loại có chủ đích khỏi gói xuất ({@code table} dạng {@code bảng} hoặc {@code bảng.cột}) kèm lý do. */
public record ExcludedItem(String table, String reason) {
}
