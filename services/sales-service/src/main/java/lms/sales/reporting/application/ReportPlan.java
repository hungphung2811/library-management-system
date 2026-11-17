package lms.sales.reporting.application;

/** Kết quả bước chuẩn bị báo cáo (trước byte đầu tiên): số dòng sẽ ghi, có bị cắt không, tên file. */
public record ReportPlan(String report, String filename, long totalRows, int rows, boolean truncated) {
}
