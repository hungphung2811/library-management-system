package lms.api.identity.web;

import lms.api.identity.client.DepartmentClient;
import lms.api.identity.dto.DepartmentDtos.ChangeStatusRequest;
import lms.api.identity.dto.DepartmentDtos.CreateDepartmentRequest;
import lms.api.identity.dto.DepartmentDtos.DepartmentNodeView;
import lms.api.identity.dto.DepartmentDtos.DepartmentView;
import lms.api.identity.dto.DepartmentDtos.UpdateDepartmentRequest;
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

/** API phòng ban cho FE. Quyền do identity-service kiểm trên token được chuyển tiếp. */
@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    private final DepartmentClient departments;

    public DepartmentController(DepartmentClient departments) {
        this.departments = departments;
    }

    @PostMapping
    public ResponseEntity<DepartmentView> create(@Valid @RequestBody CreateDepartmentRequest request) {
        DepartmentView created = departments.create(request);
        return ResponseEntity.created(URI.create("/api/departments/" + created.id())).body(created);
    }

    @GetMapping
    public List<DepartmentView> list(@RequestParam(required = false) String status) {
        return departments.list(status);
    }

    @GetMapping("/tree")
    public List<DepartmentNodeView> tree(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return departments.tree(includeInactive);
    }

    @GetMapping("/{id}")
    public DepartmentView get(@PathVariable UUID id) {
        return departments.get(id);
    }

    @PutMapping("/{id}")
    public DepartmentView update(@PathVariable UUID id, @Valid @RequestBody UpdateDepartmentRequest request) {
        return departments.update(id, request);
    }

    @PutMapping("/{id}/status")
    public DepartmentView changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return departments.changeStatus(id, request);
    }
}
