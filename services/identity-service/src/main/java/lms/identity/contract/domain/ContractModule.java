package lms.identity.contract.domain;

import java.util.Locale;

/**
 * Module thương mại mà hợp đồng bao phủ. Trùng tên với {@code tenant.domain.ModuleCode} (contract chỉ dùng tầng
 * application của tenant nên giữ bản riêng, giao tiếp bằng tên).
 */
public enum ContractModule {
    LENDING,
    SALES;

    public static ContractModule parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ContractException(ContractException.Reason.UNKNOWN_MODULE, "Mã module không được để trống");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ContractException(ContractException.Reason.UNKNOWN_MODULE, "Module không tồn tại: " + value);
        }
    }
}
