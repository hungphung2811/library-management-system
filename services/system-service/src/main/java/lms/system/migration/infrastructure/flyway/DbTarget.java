package lms.system.migration.infrastructure.flyway;

import java.util.List;

/** Thông tin kết nối và vị trí script của một DB đích. */
public record DbTarget(String url, String username, String password, List<String> locations) {
}
