package lms.api.system.web;

import lms.api.system.client.SystemClient;
import lms.api.system.dto.MenuDtos.MenuNodeView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Menu của người dùng hiện tại (mọi người đăng nhập). system-service lọc theo admin/permissions trong JWT. */
@RestController
@RequestMapping("/api/menus")
public class MenuController {

    private final SystemClient system;

    public MenuController(SystemClient system) {
        this.system = system;
    }

    @GetMapping("/me")
    public List<MenuNodeView> myMenu() {
        return system.myMenu();
    }
}
