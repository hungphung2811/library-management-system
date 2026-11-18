package lms.catalog.catalog.infrastructure.web;

import lms.catalog.catalog.application.imports.ReferenceImportHandler;
import lms.imports.engine.ImportWorkflow;
import lms.imports.web.AbstractImportController;
import lms.imports.web.ImportActorResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhập hàng loạt tác giả từ Excel (ADR-0024). Module lõi. Khóa phân quyền sinh theo tên lớp:
 * {@code AuthorImportController/upload} và {@code AuthorImportController/confirm}; không đổi tên lớp/method.
 */
@RestController
@RequestMapping("/internal/imports/authors")
public class AuthorImportController extends AbstractImportController {

    public AuthorImportController(ImportWorkflow workflow, @Qualifier("authorImportHandler") ReferenceImportHandler handler,
                                  ImportActorResolver actors) {
        super(workflow, handler, actors);
    }
}
