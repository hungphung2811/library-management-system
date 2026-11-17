package lms.api.reports.dto;

/** Header của service cần chuyển cho FE; giá trị null nghĩa là service không gửi. */
public record ReportHeaders(String contentType, String contentDisposition, String truncated) {
}
