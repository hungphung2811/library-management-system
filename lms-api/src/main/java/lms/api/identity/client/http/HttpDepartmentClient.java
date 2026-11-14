package lms.api.identity.client.http;

import lms.api.identity.client.DepartmentClient;
import lms.api.identity.dto.DepartmentDtos.ChangeStatusRequest;
import lms.api.identity.dto.DepartmentDtos.CreateDepartmentRequest;
import lms.api.identity.dto.DepartmentDtos.DepartmentNodeView;
import lms.api.identity.dto.DepartmentDtos.DepartmentView;
import lms.api.identity.dto.DepartmentDtos.UpdateDepartmentRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Gọi API nội bộ /internal/departments/** của identity-service. */
public class HttpDepartmentClient implements DepartmentClient {

    private final RestClient client;

    public HttpDepartmentClient(RestClient client) {
        this.client = client;
    }

    @Override
    public DepartmentView create(CreateDepartmentRequest request) {
        return client.post().uri("/internal/departments").body(request).retrieve().body(DepartmentView.class);
    }

    @Override
    public List<DepartmentView> list(String status) {
        var spec = (status == null || status.isBlank())
                ? client.get().uri("/internal/departments")
                : client.get().uri("/internal/departments?status={s}", status);
        return spec.retrieve().body(new ParameterizedTypeReference<List<DepartmentView>>() { });
    }

    @Override
    public List<DepartmentNodeView> tree(boolean includeInactive) {
        return client.get().uri("/internal/departments/tree?includeInactive={i}", includeInactive).retrieve()
                .body(new ParameterizedTypeReference<List<DepartmentNodeView>>() { });
    }

    @Override
    public DepartmentView get(UUID id) {
        return client.get().uri("/internal/departments/{id}", id).retrieve().body(DepartmentView.class);
    }

    @Override
    public DepartmentView update(UUID id, UpdateDepartmentRequest request) {
        return client.put().uri("/internal/departments/{id}", id).body(request).retrieve().body(DepartmentView.class);
    }

    @Override
    public DepartmentView changeStatus(UUID id, ChangeStatusRequest request) {
        return client.put().uri("/internal/departments/{id}/status", id).body(request).retrieve()
                .body(DepartmentView.class);
    }
}
