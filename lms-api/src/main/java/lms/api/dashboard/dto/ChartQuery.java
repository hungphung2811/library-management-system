package lms.api.dashboard.dto;

/** Query biểu đồ mượn/trả. Chỉ chuyển tiếp chuỗi: service kiểm khoảng ngày (INVALID_RANGE, RANGE_TOO_LARGE, INVALID_GRANULARITY). */
public record ChartQuery(String from, String to, String granularity) {
}
