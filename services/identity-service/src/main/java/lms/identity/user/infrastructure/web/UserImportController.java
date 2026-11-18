package lms.identity.user.infrastructure.web;

import java.util.UUID;
import lms.identity.user.application.UserImportHandler;
import lms.imports.engine.ImportWorkflow;
import lms.imports.web.AbstractImportController;
import lms.imports.web.ImportActorResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhập người dùng/độc giả từ Excel (ADR-0024, loại {@code users}). 7 endpoint chung do lớp cơ sở cung cấp; khóa quyền
 * {@code UserImportController/upload} và {@code UserImportController/confirm}. Thêm {@code credentials}: tải MỘT lần file
 * mật khẩu tạm sau khi phiên COMMITTED (khóa {@code UserImportController/credentials}). KHÔNG đổi tên lớp/method: đổi khóa
 * quyền của role đã gán. Chỉ nạp ở ứng dụng web: tiến trình seed demo (profile demo-seed, không web, ADR-0018) không có
 * {@code ImportActorResolver} (bean chỉ có khi có web) nên không được nạp controller này.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/internal/imports/users")
public class UserImportController extends AbstractImportController {

    public UserImportController(ImportWorkflow workflow, UserImportHandler handler, ImportActorResolver actors) {
        super(workflow, handler, actors);
    }

    @GetMapping("/{importId}/credentials")
    public ResponseEntity<byte[]> credentials(@PathVariable UUID importId) {
        return credentialsDownload(importId);
    }
}
