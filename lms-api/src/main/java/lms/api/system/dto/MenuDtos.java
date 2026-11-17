package lms.api.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTO menu cho FE (GET /api/menus/me và /api/admin/menus). */
public final class MenuDtos {

    private MenuDtos() {
    }

    /** Nút menu của người dùng hiện tại: không lộ permissionKey/enabled. children luôn có (có thể rỗng). */
    public record MenuNodeView(UUID id, String code, String label, String path, String icon,
                               List<MenuNodeView> children) {
    }

    /** Một mục cho màn quản trị: danh sách PHẲNG (cha trước con, gồm mục tắt), FE dựng cây theo parentId. */
    public record MenuAdminNodeView(UUID id, String code, String label, String path, String icon, UUID parentId,
                                    int sortOrder, boolean enabled, String permissionKey, long version,
                                    Instant updatedAt, String module) {
    }

    /**
     * Danh sách quản trị kèm cờ: usingDefaults = true khi tenant chưa có dữ liệu menu (items rỗng; GET /api/menus/me
     * đang dùng bộ mặc định). Gọi POST /api/admin/menus/defaults để chép bộ mặc định thành dữ liệu của tenant.
     */
    public record MenuAdminListView(boolean usingDefaults, List<MenuAdminNodeView> items) {
    }

    public record CreateMenuRequest(@NotBlank @Size(max = 100) String code, @NotBlank @Size(max = 200) String label,
                                    @Size(max = 500) String path, @Size(max = 100) String icon, UUID parentId,
                                    Integer sortOrder, Boolean enabled, @Size(max = 255) String permissionKey,
                                    @Size(max = 20) String module) {
    }

    /** code không đổi được; đổi parentId là di chuyển; version để khóa lạc quan (409 MENU_VERSION_CONFLICT). */
    public record UpdateMenuRequest(@NotBlank @Size(max = 200) String label, @Size(max = 500) String path,
                                    @Size(max = 100) String icon, UUID parentId, @NotNull Integer sortOrder,
                                    @NotNull Boolean enabled, @Size(max = 255) String permissionKey,
                                    @NotNull Long version, @Size(max = 20) String module) {
    }
}
