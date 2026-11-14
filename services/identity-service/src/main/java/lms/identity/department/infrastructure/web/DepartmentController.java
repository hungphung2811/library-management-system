package lms.identity.department.infrastructure.web;

import lms.identity.department.application.ChangeDepartmentStatusRequest;
import lms.identity.department.application.ChangeDepartmentStatusUseCase;
import lms.identity.department.application.CreateDepartmentRequest;
import lms.identity.department.application.CreateDepartmentUseCase;
import lms.identity.department.application.DepartmentNode;
import lms.identity.department.application.DepartmentResponse;
import lms.identity.department.application.GetDepartmentUseCase;
import lms.identity.department.application.ListDepartmentsUseCase;
import lms.identity.department.application.UpdateDepartmentRequest;
import lms.identity.department.application.UpdateDepartmentUseCase;
import lms.identity.department.domain.DepartmentStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản lý phòng ban (cây tổ chức). Không gắn annotation quyền: mỗi method dùng khóa tự sinh
 * {@code DepartmentController/tênPhươngThức} (xem PermissionInterceptor ở security-common).
 */
@RestController
@RequestMapping("/internal/departments")
public class DepartmentController {

    private final CreateDepartmentUseCase create;
    private final UpdateDepartmentUseCase update;
    private final ChangeDepartmentStatusUseCase changeStatus;
    private final GetDepartmentUseCase get;
    private final ListDepartmentsUseCase list;

    public DepartmentController(CreateDepartmentUseCase create, UpdateDepartmentUseCase update,
                                ChangeDepartmentStatusUseCase changeStatus, GetDepartmentUseCase get,
                                ListDepartmentsUseCase list) {
        this.create = create;
        this.update = update;
        this.changeStatus = changeStatus;
        this.get = get;
        this.list = list;
    }

    @PostMapping
    public ResponseEntity<DepartmentResponse> create(@Valid @RequestBody CreateDepartmentRequest request) {
        DepartmentResponse response = create.execute(request);
        return ResponseEntity.created(URI.create("/internal/departments/" + response.id())).body(response);
    }

    @GetMapping
    public List<DepartmentResponse> list(@RequestParam(required = false) DepartmentStatus status) {
        return list.list(status);
    }

    @GetMapping("/tree")
    public List<DepartmentNode> tree(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return list.tree(includeInactive);
    }

    @GetMapping("/{id}")
    public DepartmentResponse get(@PathVariable UUID id) {
        return get.execute(id);
    }

    @PutMapping("/{id}")
    public DepartmentResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateDepartmentRequest request) {
        return update.execute(id, request);
    }

    @PutMapping("/{id}/status")
    public DepartmentResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeDepartmentStatusRequest request) {
        return changeStatus.execute(id, request.status());
    }
}
