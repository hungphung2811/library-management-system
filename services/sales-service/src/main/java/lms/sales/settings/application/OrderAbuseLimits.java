package lms.sales.settings.application;

/**
 * Giá trị chính sách chống lạm dụng đang áp dụng cho một tenant (đã lưu hoặc mặc định), dạng chỉ-đọc cho context khác
 * (ordering) dùng qua tầng application; domain {@code OrderAbusePolicy} giữ việc kiểm hợp lệ khi ghi.
 */
public record OrderAbuseLimits(int reservationMinutes, int maxPendingOrders, int maxOrdersPerDay,
                               int maxQuantityPerLine, int maxTotalQuantity, int ordersPerMinutePerUser,
                               int ordersPerMinuteTenant) {
}
