package lms.identity.accesscontrol.infrastructure.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PermissionJpaRepository extends JpaRepository<PermissionJpaEntity, String> {

    /**
     * Đăng ký khóa phát hiện được theo kiểu atomic và idempotent (an toàn khi nhiều service/luồng cùng gọi).
     * Khóa chưa có thì chèn mới. Khóa đã có (kể cả mã seed sẵn) thì KHÔNG ghi đè mô tả hay nguồn, chỉ điền các
     * thông tin endpoint còn trống để người quản trị biết khóa này gắn với endpoint nào.
     */
    @Modifying
    @Query(value = """
            INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
            VALUES (:code, :description, :source, :service, :httpMethod, :pathPattern, :firstSeenAt)
            ON CONFLICT (code) DO UPDATE SET
                service = COALESCE(permissions.service, EXCLUDED.service),
                http_method = COALESCE(permissions.http_method, EXCLUDED.http_method),
                path_pattern = COALESCE(permissions.path_pattern, EXCLUDED.path_pattern),
                first_seen_at = COALESCE(permissions.first_seen_at, EXCLUDED.first_seen_at)
            """, nativeQuery = true)
    void registerDiscovered(@Param("code") String code, @Param("description") String description,
                            @Param("source") String source, @Param("service") String service,
                            @Param("httpMethod") String httpMethod, @Param("pathPattern") String pathPattern,
                            @Param("firstSeenAt") Instant firstSeenAt);
}
