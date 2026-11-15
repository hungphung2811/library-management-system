package lms.identity.tenant.domain;

import java.util.UUID;

/** Điều kiện tìm tenant cho người vận hành; trường null = không lọc. keyword khớp mã hoặc tên (không phân biệt hoa thường). */
public record TenantCriteria(String keyword, TenantStatus status, UUID organizationId, int page, int size) {
}
