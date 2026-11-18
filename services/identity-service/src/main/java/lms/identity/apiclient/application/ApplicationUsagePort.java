package lms.identity.apiclient.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Đọc bộ đếm sử dụng theo ngày do integration-api ghi (SAAS_API_AUTHENTICATION_DESIGN 4.4.3). Chỉ đọc; không có số
 * liệu nào khác ngoài tổng yêu cầu mỗi ngày (UTC).
 */
public interface ApplicationUsagePort {

    /**
     * @return tổng yêu cầu của từng ngày có dữ liệu (ngày không có khóa thì vắng mặt trong map)
     * @throws lms.identity.apiclient.domain.ApplicationException USAGE_UNAVAILABLE khi kho bộ đếm lỗi
     */
    Map<LocalDate, Long> dailyRequests(UUID tenantId, UUID applicationId, List<LocalDate> days);
}
