package lms.identity.tenant.domain;

import java.util.Locale;

/**
 * Module thương mại của tenant (ADR-0009). Tập đóng, trùng tên với {@code lms.security.permission.TenantModule}
 * (security-common) và với cột {@code tenant_modules.module}; domain giữ bản riêng để không phụ thuộc thư viện ngoài.
 */
public enum ModuleCode {
    LENDING,
    SALES;

    /** Module mặc định khi tạo tenant không nêu module (tenant hiện có đều được backfill LENDING). */
    public static final ModuleCode DEFAULT = LENDING;

    /** Không phân biệt hoa thường, bỏ khoảng trắng đầu/cuối. */
    public static ModuleCode parse(String value) {
        if (value == null || value.isBlank()) throw new UnknownModuleException("Mã module không được để trống");
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnknownModuleException("Module không tồn tại: " + value);
        }
    }
}
