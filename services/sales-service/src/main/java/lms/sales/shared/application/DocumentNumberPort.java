package lms.sales.shared.application;

import java.util.UUID;

/** Sinh số chứng từ dễ đọc theo tenant (bộ đếm trong cùng transaction, adapter chọn cách cài). */
public interface DocumentNumberPort {

    String ORDER = "ORDER";
    String RECEIPT = "RECEIPT";
    String RETURN = "RETURN";

    /** Số ca thu ngân (ADR-0030), hiển thị {@code CA-000001}. */
    String SHIFT = "SHIFT";

    String next(UUID tenantId, String sequence);

    /**
     * Tăng bộ đếm và trả số nguyên thô (không tiền tố), cùng transaction như {@link #next}. Dùng cho số hóa đơn
     * (tên bộ đếm tối đa 30 ký tự, cột {@code sales_counters.name}).
     */
    long nextValue(UUID tenantId, String sequence);
}
