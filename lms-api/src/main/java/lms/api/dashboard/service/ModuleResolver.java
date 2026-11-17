package lms.api.dashboard.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lms.api.dashboard.client.PlatformModulesClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Xác định module bật của tenant (ADR-0018 mục 2.1): token có tenantId thì đọc claim {@code modules}; platform admin
 * (platformAdmin, không tenantId) kèm {@code X-Tenant-Id} thì hỏi identity vì ADR-0016 bỏ qua kiểm module ở service.
 */
public class ModuleResolver {

    /** Tên claim trùng JwtClaims của security-common (lms-api không phụ thuộc thư viện đó). */
    static final String CLAIM_TENANT_ID = "tenantId";
    static final String CLAIM_PLATFORM_ADMIN = "platformAdmin";
    static final String CLAIM_MODULES = "modules";

    private final PlatformModulesClient platformModules;

    public ModuleResolver(PlatformModulesClient platformModules) {
        this.platformModules = platformModules;
    }

    /**
     * Lỗi 4xx của identity (tenant không có, token không phải platform admin) được ném lại để trả nguyên cho FE.
     */
    public ModuleScope resolve(Jwt jwt, String tenantHeader) {
        boolean noTenantInToken = jwt.getClaimAsString(CLAIM_TENANT_ID) == null;
        boolean platformAdmin = Boolean.TRUE.equals(jwt.getClaim(CLAIM_PLATFORM_ADMIN));
        if (noTenantInToken && platformAdmin) {
            UUID target = parseUuid(tenantHeader);
            if (target == null) {
                // không có header hoặc sai định dạng: service sẽ trả 403 TENANT_REQUIRED / 400 INVALID_TENANT_ID
                return ModuleScope.unknown();
            }
            try {
                return new ModuleScope(platformModules.enabledModules(target), false, target.toString());
            } catch (RestClientResponseException ex) {
                if (!ex.getStatusCode().is5xxServerError()) {
                    throw ex;
                }
            } catch (ResourceAccessException ex) {
                // rơi xuống: không xác định được module
            }
            return new ModuleScope(null, true, target.toString());
        }
        if (jwt.hasClaim(CLAIM_MODULES)) {
            List<String> claim = jwt.getClaimAsStringList(CLAIM_MODULES);
            Set<String> modules = new HashSet<>();
            if (claim != null) {
                claim.forEach(m -> modules.add(m.toUpperCase(Locale.ROOT)));
            }
            return new ModuleScope(modules, false, null);
        }
        return ModuleScope.unknown();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
