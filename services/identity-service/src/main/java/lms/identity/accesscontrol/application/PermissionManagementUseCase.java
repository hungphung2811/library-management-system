package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.application.AccessDtos.PermissionResponse;
import lms.identity.accesscontrol.application.AccessMapper;
import lms.identity.accesscontrol.application.AccessCommands.CreatePermission;
import lms.identity.accesscontrol.application.SavePermissionPort;
import lms.identity.accesscontrol.domain.DuplicateAccessControlException;
import lms.identity.accesscontrol.domain.Permission;
import lms.identity.accesscontrol.domain.PermissionRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class PermissionManagementUseCase {

    private final PermissionRepository repository;
    private final SavePermissionPort savePort;

    public PermissionManagementUseCase(PermissionRepository repository, SavePermissionPort savePort) {
        this.repository = repository;
        this.savePort = savePort;
    }

    @Transactional
    public PermissionResponse create(CreatePermission command) {
        Permission permission = new Permission(command.code(), command.description());
        if (repository.existsByCode(permission.code())) {
            throw new DuplicateAccessControlException("Permission đã tồn tại: " + permission.code());
        }
        return AccessMapper.toResponse(savePort.save(permission));
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> list() {
        return repository.findAllPermissions().stream().map(AccessMapper::toResponse).toList();
    }
}
