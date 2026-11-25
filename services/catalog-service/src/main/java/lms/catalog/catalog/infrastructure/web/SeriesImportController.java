package lms.catalog.catalog.infrastructure.web;

import lms.catalog.catalog.application.imports.ReferenceImportHandler;
import lms.imports.engine.ImportWorkflow;
import lms.imports.web.AbstractImportController;
import lms.imports.web.ImportActorResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhập hàng loạt bộ sách từ Excel (ADR-0024, ADR-0026 mục 3.8). Module lõi. Khóa phân quyền sinh theo tên lớp:
 * {@code SeriesImportController/upload} và {@code SeriesImportController/confirm}; không đổi tên lớp/method.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/imports/series")
public class SeriesImportController extends AbstractImportController {

    public SeriesImportController(ImportWorkflow workflow, @Qualifier("seriesImportHandler") ReferenceImportHandler handler,
                                  ImportActorResolver actors) {
        super(workflow, handler, actors);
    }
}
