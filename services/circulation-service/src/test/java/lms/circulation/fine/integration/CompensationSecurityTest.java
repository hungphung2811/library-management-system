package lms.circulation.fine.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lms.circulation.borrowing.application.BorrowBookUseCase;
import lms.circulation.borrowing.application.GetBorrowingUseCase;
import lms.circulation.borrowing.application.ListBorrowingsUseCase;
import lms.circulation.borrowing.application.RenewBorrowingUseCase;
import lms.circulation.borrowing.application.ReportFoundUseCase;
import lms.circulation.borrowing.application.ReportLostUseCase;
import lms.circulation.borrowing.application.ReturnBookUseCase;
import lms.circulation.borrowing.application.SearchBorrowingsUseCase;
import lms.circulation.borrowing.infrastructure.web.BorrowingController;
import lms.circulation.config.CompensationExceptionHandler;
import lms.circulation.config.SecurityConfiguration;
import lms.circulation.fine.application.FineAdjustmentUseCases;
import lms.circulation.fine.application.FineRefundUseCases;
import lms.circulation.fine.domain.FineRuleException;
import lms.circulation.fine.infrastructure.web.FineAdjustmentController;
import lms.circulation.fine.infrastructure.web.FineRefundController;
import lms.circulation.policy.application.CreateDamageLevelUseCase;
import lms.circulation.policy.application.DeleteDamageLevelUseCase;
import lms.circulation.policy.application.UpdateCompensationPolicyUseCase;
import lms.circulation.policy.application.UpdateDamageLevelUseCase;
import lms.circulation.policy.infrastructure.cache.CachedCompensationReader;
import lms.circulation.policy.infrastructure.web.CompensationPolicyController;
import lms.circulation.policy.infrastructure.web.DamageLevelController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.security.permission.PermissionKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * RBAC của các controller đền bù mới (tiêu chí 15) và đối chiếu V35__compensation_permissions.sql với controller THẬT:
 * khóa, HTTP method, đường dẫn /api/..., service. Đổi tên lớp/method hoặc đường dẫn mà không sửa migration làm test fail.
 */
@WebMvcTest({DamageLevelController.class, CompensationPolicyController.class, FineAdjustmentController.class,
        FineRefundController.class, BorrowingController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, CompensationExceptionHandler.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class CompensationSecurityTest {

    private static final Pattern ROW = Pattern.compile(
            "\\('([A-Za-z]+/[A-Za-z]+)',\\s*'circulation-service',\\s*'([A-Z]+)',\\s*'([^']+)'\\)");
    private static final java.util.Set<String> NEW_CONTROLLERS = java.util.Set.of("DamageLevelController",
            "CompensationPolicyController", "FineAdjustmentController", "FineRefundController");

    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerMapping handlerMapping;

    @MockitoBean CachedCompensationReader reader;
    @MockitoBean CreateDamageLevelUseCase createLevel;
    @MockitoBean UpdateDamageLevelUseCase updateLevel;
    @MockitoBean DeleteDamageLevelUseCase deleteLevel;
    @MockitoBean UpdateCompensationPolicyUseCase updatePolicy;
    @MockitoBean FineAdjustmentUseCases adjustments;
    @MockitoBean FineRefundUseCases refunds;
    @MockitoBean BorrowBookUseCase borrowBook;
    @MockitoBean ReturnBookUseCase returnBook;
    @MockitoBean RenewBorrowingUseCase renewBorrowing;
    @MockitoBean GetBorrowingUseCase getBorrowing;
    @MockitoBean ReportLostUseCase reportLost;
    @MockitoBean ListBorrowingsUseCase listBorrowings;
    @MockitoBean SearchBorrowingsUseCase searchBorrowings;
    @MockitoBean ReportFoundUseCase reportFound;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor withKeys(UUID user, String... keys) {
        return jwt().jwt(j -> j.subject(user.toString()).claim("tenantId", UUID.randomUUID().toString())
                .claim("permissions", List.of(keys)))
                .authorities(java.util.Arrays.stream(keys)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .toArray(org.springframework.security.core.authority.SimpleGrantedAuthority[]::new));
    }

    @Test
    void khongTokenBi401VaKhongCoKhoaBi403() throws Exception {
        mvc.perform(get("/internal/circulation/damage-levels")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/circulation/damage-levels").with(jwt().jwt(j -> j.claim("tenantId", UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/internal/circulation/compensation-policy").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(withKeys(UUID.randomUUID(), "CompensationPolicyController/get")))
                .andExpect(status().isForbidden());
    }

    @Test
    void khoaDeNghiVaKhoaDuyetTachRiengNguoiCoKhoaDeNghiKhongDuyetDuoc() throws Exception {
        UUID requestId = UUID.randomUUID();
        mvc.perform(post("/internal/fines/adjustment-requests/{id}/approve", requestId).contentType(MediaType.APPLICATION_JSON)
                        .content("{}").with(withKeys(UUID.randomUUID(), "FineAdjustmentController/request")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/fines/refund-requests/{id}/approve", requestId).contentType(MediaType.APPLICATION_JSON)
                        .content("{}").with(withKeys(UUID.randomUUID(), "BorrowingController/reportFound")))
                .andExpect(status().isForbidden());
    }

    @Test
    void nguoiCoKhoaDuyetNhungLaNguoiDeNghiNhan409ApproverMustDiffer() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID self = UUID.randomUUID();
        when(adjustments.approve(any(), any(), any())).thenThrow(
                FineRuleException.conflict("APPROVER_MUST_DIFFER", "Người duyệt phải khác người đề nghị"));
        mvc.perform(post("/internal/fines/adjustment-requests/{id}/approve", requestId).contentType(MediaType.APPLICATION_JSON)
                        .content("{}").with(withKeys(self, "FineAdjustmentController/approve")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVER_MUST_DIFFER"));
        when(refunds.reject(any(), any(), any())).thenThrow(
                FineRuleException.conflict("APPROVER_MUST_DIFFER", "Người duyệt phải khác người đề nghị"));
        mvc.perform(post("/internal/fines/refund-requests/{id}/reject", requestId).contentType(MediaType.APPLICATION_JSON)
                        .content("{}").with(withKeys(self, "FineRefundController/reject")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVER_MUST_DIFFER"));
    }

    @Test
    void duyetKhongCanBodyVaLoiNghiepVuDuocAnhXaMaHttp() throws Exception {
        when(adjustments.request(any(), any(), any())).thenThrow(
                FineRuleException.invalid("INVALID_ADJUSTMENT_AMOUNT", "Số tiền không hợp lệ"));
        mvc.perform(post("/internal/fines/{id}/adjustment-requests", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REDUCE\",\"payableAmount\":1,\"reason\":\"x\"}")
                        .with(withKeys(UUID.randomUUID(), "FineAdjustmentController/request")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ADJUSTMENT_AMOUNT"));
        // thiếu lý do (bắt buộc)
        mvc.perform(post("/internal/fines/{id}/adjustment-requests", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"WAIVE\"}").with(withKeys(UUID.randomUUID(), "FineAdjustmentController/request")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void platformAdminKhongTenantKhongDuyetDuoc() throws Exception {
        mvc.perform(post("/internal/fines/adjustment-requests/{id}/approve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void seedV35KhopControllerThat() throws Exception {
        var found = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/identity/V35__compensation_permissions.sql");
        assertThat(found).hasSize(1);
        String sql;
        try (var in = found[0].getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> seeded = new TreeMap<>();
        Matcher m = ROW.matcher(sql);
        while (m.find()) seeded.put(m.group(1), m.group(2) + " " + m.group(3).replaceAll("\\{[^}/]+}", "{}"));

        PermissionKeyResolver resolver = new PermissionKeyResolver();
        Map<String, String> actual = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            String cls = h.getBeanType().getSimpleName();
            String key = resolver.resolve(h).key();
            if (key == null) continue;
            if (!NEW_CONTROLLERS.contains(cls) && !key.equals("BorrowingController/reportFound")) continue;
            String method = e.getKey().getMethodsCondition().getMethods().iterator().next().name();
            String path = e.getKey().getPathPatternsCondition().getPatternValues().iterator().next()
                    .replace("/internal/", "/api/");
            actual.put(key, method + " " + path.replaceAll("\\{[^}/]+}", "{}"));
        }
        assertThat(actual).hasSize(14);
        assertThat(seeded).isEqualTo(actual);
    }
}
