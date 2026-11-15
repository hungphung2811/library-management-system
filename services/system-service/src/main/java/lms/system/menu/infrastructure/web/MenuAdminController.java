package lms.system.menu.infrastructure.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lms.system.menu.application.CreateMenuUseCase;
import lms.system.menu.application.DeleteMenuUseCase;
import lms.system.menu.application.ListMenusUseCase;
import lms.system.menu.application.UpdateMenuUseCase;
import lms.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản trị menu. Khóa quyền động mặc định MenuAdminController/{listMenus,createMenu,updateMenu,deleteMenu}
 * (ADR-0003): admin dùng ngay, người khác cần được gán khóa. Đặt tên xong không đổi (đổi là role mất quyền).
 */
@RestController
@RequestMapping("/internal/menus")
public class MenuAdminController {

    private final ListMenusUseCase listMenus;
    private final CreateMenuUseCase createMenu;
    private final UpdateMenuUseCase updateMenu;
    private final DeleteMenuUseCase deleteMenu;

    public MenuAdminController(ListMenusUseCase listMenus, CreateMenuUseCase createMenu,
                               UpdateMenuUseCase updateMenu, DeleteMenuUseCase deleteMenu) {
        this.listMenus = listMenus;
        this.createMenu = createMenu;
        this.updateMenu = updateMenu;
        this.deleteMenu = deleteMenu;
    }

    @GetMapping
    public List<MenuResponses.AdminNode> listMenus() {
        return listMenus.execute(TenantContext.require()).stream().map(MenuResponses.AdminNode::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MenuResponses.AdminNode createMenu(@Valid @RequestBody MenuRequests.CreateRequest request) {
        return MenuResponses.AdminNode.from(createMenu.execute(TenantContext.require(), request.toCommand()));
    }

    @PutMapping("/{id}")
    public MenuResponses.AdminNode updateMenu(@PathVariable UUID id,
                                              @Valid @RequestBody MenuRequests.UpdateRequest request) {
        return MenuResponses.AdminNode.from(updateMenu.execute(TenantContext.require(), request.toCommand(id)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMenu(@PathVariable UUID id) {
        deleteMenu.execute(TenantContext.require(), id);
    }
}
