package lms.sales.ordering.application;

import java.util.UUID;

/**
 * Khóa tư vấn (advisory) theo (tenant, khách) đến hết transaction hiện tại: tuần tự hóa các lần đặt đơn đồng thời của
 * CÙNG một khách để kiểm trần số đơn chờ/số đơn trong ngày không bị vượt khi nhiều request đến cùng lúc. Khách và
 * tenant khác nhau không chặn nhau. Phải gọi trong transaction đang mở.
 */
public interface CustomerOrderLockPort {

    void lock(UUID tenantId, UUID customerUserId);
}
