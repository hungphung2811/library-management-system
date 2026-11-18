package lms.circulation.policy.application;

import java.util.List;

/**
 * custom=false: tenant chưa cấu hình, items là hạt giống mặc định (MINOR 20%, MAJOR 50%) chưa ghi vào DB; tạo bậc đầu
 * tiên thì danh sách chỉ còn các bậc của tenant. maxLevels là giới hạn số bậc mỗi tenant.
 */
public record DamageLevelListResponse(boolean custom, int maxLevels, List<DamageLevelResponse> items) {
}
