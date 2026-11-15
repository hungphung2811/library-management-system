package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.Organization;
import lms.identity.tenant.domain.OrganizationNotFoundException;
import lms.identity.tenant.domain.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý trường (organization): tạo, đổi tên, xem, liệt kê. Không xóa (tenant tham chiếu tới). Gán/đổi trường cho
 * tenant đi qua {@link UpdateTenantUseCase}.
 *
 * <p>Audit: thao tác tổ chức không thuộc tenant nào, mà audit của support chỉ ghi sự kiện có tenantId, nên không phát
 * sự kiện Kafka (sẽ bị bỏ qua) mà ghi log INFO có cấu trúc (người thao tác, loại thao tác, organizationId). Giới hạn đã
 * biết, ghi ở ADR-0006: chưa có kho audit cấp nền tảng.
 */
public class ManageOrganizationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManageOrganizationUseCase.class);

    private final OrganizationRepository organizations;

    public ManageOrganizationUseCase(OrganizationRepository organizations) {
        this.organizations = organizations;
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
        log.info("platform-audit operation={} actorId={} organizationId={} name=\"{}\"", operation, actorId,
                organization.id(), organization.name());
    }
}
