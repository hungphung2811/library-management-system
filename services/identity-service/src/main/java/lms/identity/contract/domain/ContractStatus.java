package lms.identity.contract.domain;

/** Trạng thái lưu của hợp đồng. ACTIVE = đã chốt, có hiệu lực pháp lý (pha UPCOMING/IN_EFFECT/IN_GRACE suy ra). */
public enum ContractStatus {
    DRAFT, ACTIVE, EXPIRED, CANCELLED
}
