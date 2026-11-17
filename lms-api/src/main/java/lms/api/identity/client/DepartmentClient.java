package lms.api.identity.client;

import lms.api.identity.dto.DepartmentDtos.ChangeStatusRequest;
import lms.api.identity.dto.DepartmentDtos.CreateDepartmentRequest;
import lms.api.identity.dto.DepartmentDtos.DepartmentNodeView;
import lms.api.identity.dto.DepartmentDtos.DepartmentView;
import lms.api.identity.dto.DepartmentDtos.UpdateDepartmentRequest;
import java.util.List;
import java.util.UUID;

/** Cổng sang API phòng ban của identity-service. */
public interface DepartmentClient {

    DepartmentView create(CreateDepartmentRequest request);

    List<DepartmentView> list(String status, String tenantId);

    List<DepartmentNodeView> tree(boolean includeInactive, String tenantId);

    DepartmentView get(UUID id);

    DepartmentView update(UUID id, UpdateDepartmentRequest request);

    DepartmentView changeStatus(UUID id, ChangeStatusRequest request);
}
