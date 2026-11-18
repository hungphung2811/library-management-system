package lms.sales.stock.infrastructure.web;

import lms.imports.engine.ImportWorkflow;
import lms.imports.web.AbstractImportController;
import lms.imports.web.ImportActorResolver;
import lms.sales.stock.application.imports.StockItemImportHandler;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhập mặt hàng từ Excel (ADR-0024). Khóa quyền: {@code StockItemImportController/upload} và
 * {@code StockItemImportController/confirm}. KHÔNG đổi tên lớp hay method (đổi khóa, role mất quyền).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/imports/stock-items")
@RequiresModule(TenantModule.SALES)
public class StockItemImportController extends AbstractImportController {

    public StockItemImportController(ImportWorkflow workflow, StockItemImportHandler handler,
                                     ImportActorResolver actors) {
        super(workflow, handler, actors);
    }
}
