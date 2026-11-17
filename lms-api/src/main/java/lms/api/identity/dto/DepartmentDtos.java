package lms.api.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Hợp đồng FE cho phòng ban. */
public final class DepartmentDtos {

    private DepartmentDtos() {
    }

    public record CreateDepartmentRequest(@NotBlank @Size(max = 50) String code,
                                          @NotBlank @Size(max = 255) String name, UUID parentId) {
    }

    public record UpdateDepartmentRequest(@NotBlank @Size(max = 255) String name, UUID parentId) {
    }

    public record ChangeStatusRequest(@NotBlank String status) {
    }

    /** tenantId: tenant của dòng (ADR-0008); null khi service chưa trả. */
    public record DepartmentView(UUID id, String code, String name, UUID parentId, String status, UUID tenantId) {
    }

    public record DepartmentNodeView(UUID id, String code, String name, String status,
                                     List<DepartmentNodeView> children, UUID tenantId) {
    }
}
