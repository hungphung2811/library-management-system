package lms.api.system.web;

import lms.api.system.client.SystemClient;
import lms.api.system.dto.MenuDtos.CreateMenuRequest;
import lms.api.system.dto.MenuDtos.MenuAdminListView;
import lms.api.system.dto.MenuDtos.MenuAdminNodeView;
import lms.api.system.dto.MenuDtos.UpdateMenuRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Quản trị menu. Quyền do system-service kiểm trên token được chuyển tiếp (khóa MenuAdminController/*); lms-api không kiểm. */
@RestController
@RequestMapping("/api/admin/menus")
public class MenuAdminController {

    private final SystemClient system;

    public MenuAdminController(SystemClient system) {
        this.system = system;
    }

    @GetMapping
    public MenuAdminListView list() {
        return system.listMenus();
    }

    /** Chép menu mặc định thành dữ liệu của tenant; đã có dữ liệu thì 409 MENU_ALREADY_INITIALIZED. */
    @PostMapping("/defaults")
    public ResponseEntity<MenuAdminListView> initializeDefaults() {
        return ResponseEntity.status(201).body(system.initializeDefaultMenus());
    }

    @PostMapping
    public ResponseEntity<MenuAdminNodeView> create(@Valid @RequestBody CreateMenuRequest request) {
        MenuAdminNodeView created = system.createMenu(request);
        return ResponseEntity.created(URI.create("/api/admin/menus/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public MenuAdminNodeView update(@PathVariable UUID id, @Valid @RequestBody UpdateMenuRequest request) {
        return system.updateMenu(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        system.deleteMenu(id);
        return ResponseEntity.noContent().build();
    }
}
