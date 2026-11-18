package lms.circulation.fine.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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
import lms.circulation.fine.application.FineAdjustmentResponse;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Platform admin duyệt thay tenant (ADR-0008/0016, câu hỏi mở 6 của đề bài): cần X-Tenant-Id; bỏ qua kiểm khóa; id người
 * duyệt đưa vào use case là sub của token platform admin, nên vẫn bị kiểm APPROVER_MUST_DIFFER nếu chính họ đã đề nghị.
 * Người không phải platform admin gửi X-Tenant-Id khác thì header bị bỏ qua (không vượt tenant của token).
 */
@WebMvcTest({DamageLevelController.class, CompensationPolicyController.class, FineAdjustmentController.class,
        FineRefundController.class, BorrowingController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, CompensationExceptionHandler.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class CompensationPlatformAdminTest {

    @Autowired MockMvc mvc;

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

    @Test
    void platformAdminCoHeaderDuyetDuocKhongCanKhoaVaIdNguoiDuyetLaSub() throws Exception {
        UUID sub = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(adjustments.approve(eq(requestId), any(), eq(sub))).thenReturn(null);
        mvc.perform(post("/internal/fines/adjustment-requests/{id}/approve", requestId)
                        .header("X-Tenant-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(jwt().jwt(j -> j.subject(sub.toString()).claim("platformAdmin", true))))
                .andExpect(status().isOk());
        ArgumentCaptor<UUID> approver = ArgumentCaptor.forClass(UUID.class);
        verify(adjustments).approve(eq(requestId), any(), approver.capture());
        assertThat(approver.getValue()).isEqualTo(sub);
    }

    @Test
    void platformAdminTuDuyetDeNghiCuaChinhMinhBiApproverMustDiffer() throws Exception {
        UUID sub = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(refunds.approve(eq(requestId), any(), eq(sub))).thenThrow(
                FineRuleException.conflict("APPROVER_MUST_DIFFER", "Người duyệt phải khác người đề nghị"));
        mvc.perform(post("/internal/fines/refund-requests/{id}/approve", requestId)
                        .header("X-Tenant-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(jwt().jwt(j -> j.subject(sub.toString()).claim("platformAdmin", true))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPROVER_MUST_DIFFER"));
    }

    @Test
    void nguoiThuongGuiHeaderTenantKhacKhongDoiTenantVaKhongCoKhoaVanBi403() throws Exception {
        mvc.perform(post("/internal/fines/refund-requests/{id}/approve", UUID.randomUUID())
                        .header("X-Tenant-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                .claim("tenantId", UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void taiKhoanMustChangePasswordKhongMangKhoaNenKhongDuyetDuoc() throws Exception {
        // token mustChangePassword: không có admin/permissions (ADR-0003) dù role gốc có khóa duyệt
        mvc.perform(post("/internal/fines/adjustment-requests/{id}/approve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                .claim("tenantId", UUID.randomUUID().toString()).claim("mustChangePassword", true))))
                .andExpect(status().isForbidden());
        FineAdjustmentResponse unused = null;
        assertThat(unused).isNull();
    }
}
