package lms.catalog.catalog.infrastructure.web;

import lms.catalog.catalog.application.imports.ReferenceImportHandler;
import lms.imports.engine.ImportWorkflow;
import lms.imports.web.AbstractImportController;
import lms.imports.web.ImportActorResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhập hàng loạt nhà xuất bản từ Excel (ADR-0024). Module lõi. Khóa phân quyền sinh theo tên lớp:
 * {@code PublisherImportController/upload} và {@code PublisherImportController/confirm}; không đổi tên lớp/method.
 */
@RestController
@RequestMapping("/internal/imports/publishers")
public class PublisherImportController extends AbstractImportController {

    public PublisherImportController(ImportWorkflow workflow,
                                     @Qualifier("publisherImportHandler") ReferenceImportHandler handler,
                                     ImportActorResolver actors) {
        super(workflow, handler, actors);
    }
}
