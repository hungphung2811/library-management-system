package lms.catalog.catalog.infrastructure.web;

import lms.catalog.catalog.application.imports.TitleImportHandler;
import lms.imports.engine.ImportWorkflow;
import lms.imports.web.AbstractImportController;
import lms.imports.web.ImportActorResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhập hàng loạt đầu sách (sheet DauSach) và bản sao (sheet BanSao) từ Excel (ADR-0024). Module lõi: dòng bản sao cần
 * LENDING và bị báo lỗi dòng nếu tenant chưa bật. Khóa phân quyền: {@code TitleImportController/upload} và
 * {@code TitleImportController/confirm}; không đổi tên lớp/method (đổi khóa).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/imports/titles")
public class TitleImportController extends AbstractImportController {

    public TitleImportController(ImportWorkflow workflow, TitleImportHandler handler, ImportActorResolver actors) {
        super(workflow, handler, actors);
    }
}
