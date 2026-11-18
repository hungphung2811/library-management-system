package lms.catalog.catalog.infrastructure.web;

import lms.catalog.catalog.application.imports.ReferenceImportHandler;
import lms.imports.engine.ImportWorkflow;
import lms.imports.web.AbstractImportController;
import lms.imports.web.ImportActorResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhập hàng loạt thể loại (cha ghi trước con) từ Excel (ADR-0024). Module lõi. Khóa phân quyền sinh theo tên lớp:
 * {@code CategoryImportController/upload} và {@code CategoryImportController/confirm}; không đổi tên lớp/method.
 */
@RestController
@RequestMapping("/internal/imports/categories")
public class CategoryImportController extends AbstractImportController {

    public CategoryImportController(ImportWorkflow workflow,
                                    @Qualifier("categoryImportHandler") ReferenceImportHandler handler,
                                    ImportActorResolver actors) {
        super(workflow, handler, actors);
    }
}
