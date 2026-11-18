package lms.identity.auth.application;

import java.time.Duration;
import java.util.UUID;

/**
 * Phát token dịch vụ xuất/xóa dữ liệu tenant (ADR-0031 D1): claim {@code svc = tenant-data}, {@code aud =
 * lms-tenant-data}, {@code tenantId}, {@code permissions} rỗng, không roles/admin/platformAdmin/modules. Chỉ vào được
 * endpoint {@code @TenantDataAccess} của các service. Điều phối (gói F2/G2) gọi phương thức này; chưa có endpoint.
 */
public interface TenantDataTokenIssuerPort {

    /** TTL tối đa theo ADR-0031 D1. */
    Duration MAX_TTL = Duration.ofMinutes(10);

    TokenIssuerPort.IssuedToken issueTenantData(TenantDataClaims claims);

    /**
     * subject: UUID định danh yêu cầu xuất/xóa (hoặc id dành riêng) để truy vết, không phải người dùng; ttl phải
     * dương và không vượt {@link #MAX_TTL}.
     */
    record TenantDataClaims(UUID subject, UUID tenantId, Duration ttl) {

        public TenantDataClaims {
            if (subject == null || tenantId == null || ttl == null) {
                throw new IllegalArgumentException("subject, tenantId và ttl là bắt buộc");
            }
            if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
                throw new IllegalArgumentException("ttl token tenant-data phải trong (0, " + MAX_TTL + "]");
            }
        }

        public TenantDataClaims(UUID subject, UUID tenantId) {
            this(subject, tenantId, MAX_TTL);
        }
    }
}
