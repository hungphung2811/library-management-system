package lms.api.reports.dto;

/** Query báo cáo đơn hàng; chỉ chuyển tiếp chuỗi, sales kiểm khoảng ngày, kênh, trạng thái. */
public record OrdersReportQuery(String from, String to, String channel, String status) {
}
