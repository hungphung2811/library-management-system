package lms.identity.tenant.domain;

import java.util.List;

public record TenantPage(List<Tenant> items, long total) {
}
