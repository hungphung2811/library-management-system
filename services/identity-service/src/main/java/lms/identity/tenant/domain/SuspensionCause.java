package lms.identity.tenant.domain;

/**
 * Nguyên nhân tenant đang SUSPENDED (ADR-0012 mục 2.3.5). Chỉ {@link #CONTRACT_EXPIRED} (job hết hạn hợp đồng) được
 * tự kích hoạt lại khi một hợp đồng mới áp dụng module; {@link #MANUAL} và {@link #CONTRACT_CANCELLED} phải activate tay.
 */
public enum SuspensionCause {
    MANUAL,
    CONTRACT_EXPIRED,
    CONTRACT_CANCELLED
}
