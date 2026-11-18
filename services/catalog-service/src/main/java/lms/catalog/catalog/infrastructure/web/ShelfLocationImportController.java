package lms.catalog.catalog.infrastructure.web;

import lms.catalog.catalog.application.imports.ReferenceImportHandler;
import lms.imports.engine.ImportWorkflow;
import lms.imports.web.AbstractImportController;
import lms.imports.web.ImportActorResolver;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhập hàng loạt vị trí kệ từ Excel (ADR-0024). Thuộc module LENDING như ShelfLocationController. Khóa phân quyền sinh
 * theo tên lớp: {@code ShelfLocationImportController/upload} và {@code ShelfLocationImportController/confirm};
 * không đổi tên lớp/method.
 */
@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/imports/shelf-locations")
public class ShelfLocationImportController extends AbstractImportController {

    public ShelfLocationImportController(ImportWorkflow workflow,
                                         @Qualifier("shelfLocationImportHandler") ReferenceImportHandler handler,
                                         ImportActorResolver actors) {
        super(workflow, handler, actors);
    }
}
