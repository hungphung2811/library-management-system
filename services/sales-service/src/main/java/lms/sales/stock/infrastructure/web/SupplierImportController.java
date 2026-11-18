package lms.sales.stock.infrastructure.web;

import lms.imports.engine.ImportWorkflow;
import lms.imports.web.AbstractImportController;
import lms.imports.web.ImportActorResolver;
import lms.sales.stock.application.imports.SupplierImportHandler;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhập nhà cung cấp từ Excel (ADR-0024). Khóa quyền: {@code SupplierImportController/upload} và
 * {@code SupplierImportController/confirm}. KHÔNG đổi tên lớp hay method (đổi khóa, role mất quyền).
 */
@RestController
@RequestMapping("/internal/imports/suppliers")
@RequiresModule(TenantModule.SALES)
public class SupplierImportController extends AbstractImportController {

    public SupplierImportController(ImportWorkflow workflow, SupplierImportHandler handler,
                                    ImportActorResolver actors) {
        super(workflow, handler, actors);
    }
}
