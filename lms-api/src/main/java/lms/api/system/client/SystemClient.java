package lms.api.system.client;

import lms.api.system.dto.MenuDtos.CreateMenuRequest;
import lms.api.system.dto.MenuDtos.MenuAdminNodeView;
import lms.api.system.dto.MenuDtos.MenuNodeView;
import lms.api.system.dto.MenuDtos.UpdateMenuRequest;
import java.util.List;
import java.util.UUID;

/** Cổng sang API menu (/internal/menus/**) của system-service. */
public interface SystemClient {

    List<MenuNodeView> myMenu();

    List<MenuAdminNodeView> listMenus();

    MenuAdminNodeView createMenu(CreateMenuRequest request);

    MenuAdminNodeView updateMenu(UUID id, UpdateMenuRequest request);

    void deleteMenu(UUID id);
}
