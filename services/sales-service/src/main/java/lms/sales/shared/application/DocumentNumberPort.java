package lms.sales.shared.application;

import java.util.UUID;

/** Sinh số chứng từ dễ đọc theo tenant (bộ đếm trong cùng transaction, adapter chọn cách cài). */
public interface DocumentNumberPort {

    String ORDER = "ORDER";
    String RECEIPT = "RECEIPT";
    String RETURN = "RETURN";

    String next(UUID tenantId, String sequence);
}
