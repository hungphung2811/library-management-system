package lms.customer.storefront;

import java.util.Set;

/**
 * Nhóm tính năng công khai của một tenant (ADR-0023 2.1). Là enum nội bộ của customer-api (không import TenantModule,
 * ArchUnit cấm lms.security..): {@link #SHOP} ứng với module SALES, {@link #LIBRARY} ứng với module LENDING,
 * {@link #ANY} là một trong hai (đăng nhập, làm mới, đăng xuất, thông tin cửa hàng).
 */
public enum Feature {

    SHOP(Set.of("SALES")),
    LIBRARY(Set.of("LENDING")),
    ANY(Set.of("SALES", "LENDING"));

    private final Set<String> modules;

    Feature(Set<String> modules) {
        this.modules = modules;
    }

    /** Mã module (viết hoa) đáp ứng tính năng này. */
    public Set<String> modules() {
        return modules;
    }
}
