package lms.api.system.client.http;

import lms.api.system.client.SystemClient;
import lms.api.system.dto.MenuDtos.CreateMenuRequest;
import lms.api.system.dto.MenuDtos.MenuAdminListView;
import lms.api.system.dto.MenuDtos.MenuAdminNodeView;
import lms.api.system.dto.MenuDtos.MenuNodeView;
import lms.api.system.dto.MenuDtos.UpdateMenuRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Gọi API nội bộ /internal/menus/** của system-service. Token (kèm tenant) và correlation id do RestClient chuyển tiếp. */
public class HttpSystemClient implements SystemClient {

    private final RestClient client;

    public HttpSystemClient(RestClient client) {
        this.client = client;
    }

    @Override
    public List<MenuNodeView> myMenu() {
        List<MenuNodeView> result = client.get().uri("/internal/menus/me").retrieve()
                .body(new ParameterizedTypeReference<List<MenuNodeView>>() { });
        return result == null ? List.of() : result;
    }

    @Override
    public MenuAdminListView listMenus() {
        MenuAdminListView result = client.get().uri("/internal/menus").retrieve().body(MenuAdminListView.class);
        return result == null ? new MenuAdminListView(false, List.of()) : result;
    }

    @Override
    public MenuAdminListView initializeDefaultMenus() {
        return client.post().uri("/internal/menus/defaults").retrieve().body(MenuAdminListView.class);
    }

    @Override
    public MenuAdminNodeView createMenu(CreateMenuRequest request) {
        return client.post().uri("/internal/menus").body(request).retrieve().body(MenuAdminNodeView.class);
    }

    @Override
    public MenuAdminNodeView updateMenu(UUID id, UpdateMenuRequest request) {
        return client.put().uri("/internal/menus/{id}", id).body(request).retrieve().body(MenuAdminNodeView.class);
    }

    @Override
    public void deleteMenu(UUID id) {
        client.delete().uri("/internal/menus/{id}", id).retrieve().toBodilessEntity();
    }
}
