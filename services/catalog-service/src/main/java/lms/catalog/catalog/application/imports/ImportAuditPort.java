package lms.catalog.catalog.application.imports;

import lms.imports.core.ImportContext;
import lms.imports.core.ImportResult;

/**
 * Ghi vết MỘT bản ghi tóm tắt cho mỗi lần confirm (importId, loại, số tạo/bỏ qua/lỗi), không ghi từng dòng và không
 * chứa giá trị dòng (ADR-0024 mục 3.6, Q7). Lỗi ghi vết chỉ được log, không làm hỏng việc nhập.
 */
public interface ImportAuditPort {

    void completed(ImportContext ctx, ImportResult result);
}
