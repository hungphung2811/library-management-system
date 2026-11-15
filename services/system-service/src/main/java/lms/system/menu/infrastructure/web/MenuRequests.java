package lms.system.menu.infrastructure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lms.system.menu.application.CreateMenuUseCase;
import lms.system.menu.application.UpdateMenuUseCase;
import lms.system.menu.domain.MenuDetails;

public final class MenuRequests {

    private MenuRequests() {
    }

    /** {@code sortOrder} mặc định 0, {@code enabled} mặc định true khi bỏ trống. */
    public record CreateRequest(
            @NotBlank @Size(max = 100) String code,
            @NotBlank @Size(max = 200) String label,
            @Size(max = 500) String path,
            @Size(max = 100) String icon,
            UUID parentId,
            Integer sortOrder,
            Boolean enabled,
            @Size(max = 255) String permissionKey) {

        CreateMenuUseCase.Command toCommand() {
            return new CreateMenuUseCase.Command(code, new MenuDetails(label, path, icon, parentId,
                    sortOrder == null ? 0 : sortOrder, enabled == null || enabled, permissionKey));
        }
    }

    /** {@code code} không đổi được; đổi {@code parentId} là di chuyển. {@code version} là phiên bản đã tải. */
    public record UpdateRequest(
            @NotBlank @Size(max = 200) String label,
            @Size(max = 500) String path,
            @Size(max = 100) String icon,
            UUID parentId,
            @NotNull Integer sortOrder,
            @NotNull Boolean enabled,
            @Size(max = 255) String permissionKey,
            @NotNull Long version) {

        UpdateMenuUseCase.Command toCommand(UUID id) {
            return new UpdateMenuUseCase.Command(id,
                    new MenuDetails(label, path, icon, parentId, sortOrder, enabled, permissionKey), version);
        }
    }
}
