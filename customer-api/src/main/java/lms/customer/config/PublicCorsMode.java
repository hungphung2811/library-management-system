package lms.customer.config;

/** Chế độ CORS của /public/** (ADR-0013 2.7). */
public enum PublicCorsMode {
    /** Cho mọi origin hợp lệ cú pháp (trừ "null"), không tra tenant, không log. Dùng cho dev. */
    OFF,
    /** Quyết như ENFORCE nhưng origin bị từ chối vẫn được cho phép và ghi log "cors-would-reject". */
    REPORT,
    /** Chỉ origin nền tảng, origin dev và origin đã khai báo của tenant. Mặc định (fail-closed). */
    ENFORCE
}
