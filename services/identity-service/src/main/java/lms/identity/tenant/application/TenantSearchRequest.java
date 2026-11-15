package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.TenantStatus;

/** Tham số tìm tenant (query string). page bắt đầu từ 0, size mặc định 20, tối đa 100. */
public record TenantSearchRequest(String keyword, TenantStatus status, UUID organizationId, Integer page,
                                  Integer size) {
}
