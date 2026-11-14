package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.application.AccessCommands.RegisterPermissionKey;
import lms.identity.accesscontrol.domain.Permission;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lưu khóa quyền do endpoint sinh ra vào danh mục permission khi endpoint được gọi lần đầu. Idempotent: gọi lại hoặc
 * gọi đồng thời từ nhiều service không tạo trùng và không ghi đè mô tả hay nguồn của khóa đã có.
 */
public class RegisterPermissionKeyUseCase {

    private final SavePermissionPort savePort;

    public RegisterPermissionKeyUseCase(SavePermissionPort savePort) {
        this.savePort = savePort;
    }

    @Transactional
    public void execute(RegisterPermissionKey command) {
        String source = command.source() == null || command.source().isBlank() ? Permission.SOURCE_AUTO : command.source();
        String description = command.description() == null || command.description().isBlank()
                ? command.key() : command.description();
        // Permission kiểm tra khóa hợp lệ và nguồn trước khi chạm DB
        Permission permission = new Permission(command.key(), description, source, command.service(),
                command.httpMethod(), command.pathPattern(), command.seenAt());
        savePort.registerDiscovered(permission);
    }
}
