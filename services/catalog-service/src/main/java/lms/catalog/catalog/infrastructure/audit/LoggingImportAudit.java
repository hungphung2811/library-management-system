package lms.catalog.catalog.infrastructure.audit;

import lms.catalog.catalog.application.imports.ImportAuditPort;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bản ghi tóm tắt mỗi lần confirm nhập Excel, ghi ra logger {@code import-audit} (catalog chưa có kho audit riêng và
 * support-service chưa tiêu thụ sự kiện catalog). Chỉ định danh phiên và số liệu, không có giá trị dòng.
 */
public class LoggingImportAudit implements ImportAuditPort {

    private static final Logger AUDIT = LoggerFactory.getLogger("import-audit");

    @Override
    public void completed(ImportContext ctx, ImportResult result) {
        AUDIT.info("import completed: importId={} type={} tenant={} user={} created={} skippedDuplicate={} failed={} completed={}",
                result.importId(), result.type(), ctx.tenantId(), ctx.userId(), result.created(),
                result.skippedDuplicate(), result.failed(), result.completed());
    }
}
