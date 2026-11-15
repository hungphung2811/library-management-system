package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.Organization;
import lms.identity.tenant.domain.OrganizationNotFoundException;
import lms.identity.tenant.domain.OrganizationRepository;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý trường (organization): tạo, đổi tên, xem, liệt kê. Không xóa (tenant tham chiếu tới). Gán/đổi trường cho
 * tenant đi qua {@link UpdateTenantUseCase}. Audit phát với envelope không có tenantId (thao tác không thuộc tenant).
 */
public class ManageOrganizationUseCase {

    private final OrganizationRepository organizations;
    private final PublishPlatformEventPort events;

    public ManageOrganizationUseCase(OrganizationRepository organizations, PublishPlatformEventPort events) {
        this.organizations = organizations;
        this.events = events;
    }

    /** organizationId null hợp lệ (tenant không thuộc trường nào). */
    static void requireExists(OrganizationRepository organizations, UUID organizationId) {
        if (organizationId != null && organizations.findById(organizationId).isEmpty()) {
            throw new OrganizationNotFoundException("Không tìm thấy tổ chức " + organizationId);
        }
    }

    @Transactional
    public OrganizationResponse create(OrganizationRequest request, UUID actorId) {
        Organization created = organizations.save(Organization.create(request.name()));
        audit("ORGANIZATION_CREATED", actorId, created);
        return OrganizationResponse.from(created);
    }

    @Transactional
    public OrganizationResponse rename(UUID organizationId, OrganizationRequest request, UUID actorId) {
        Organization organization = load(organizationId);
        organization.rename(request.name());
        organizations.save(organization);
        audit("ORGANIZATION_UPDATED", actorId, organization);
        return OrganizationResponse.from(organization);
    }

    public OrganizationResponse get(UUID organizationId) {
        return OrganizationResponse.from(load(organizationId));
    }

    public List<OrganizationResponse> list() {
        return organizations.findAll().stream().map(OrganizationResponse::from).toList();
    }

    private Organization load(UUID organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException("Không tìm thấy tổ chức " + organizationId));
    }

    private void audit(String operation, UUID actorId, Organization organization) {
        // token của người vận hành có thể mang tenantId của tenant họ là thành viên: không gắn audit vào tenant đó
        TenantContext.runAs(null, () -> events.publish(PlatformOperationEvent.of(operation, actorId, null,
                organization.id(), "name=" + organization.name())));
    }
}
