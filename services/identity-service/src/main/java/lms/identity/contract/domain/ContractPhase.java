package lms.identity.contract.domain;

/** Pha suy ra của hợp đồng ACTIVE (không lưu). */
public enum ContractPhase {
    /** now &lt; starts_at: chưa áp dụng module. */
    UPCOMING,
    /** starts_at &lt;= now &lt; ends_at (legacy chưa cắt: vô hạn). */
    IN_EFFECT,
    /** ends_at &lt;= now &lt; ends_at + ân hạn: module vẫn bật. */
    IN_GRACE
}
