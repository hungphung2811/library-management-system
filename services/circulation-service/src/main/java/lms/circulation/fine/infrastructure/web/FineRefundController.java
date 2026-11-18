package lms.circulation.fine.infrastructure.web;

import jakarta.validation.Valid;
import java.util.UUID;
import lms.circulation.fine.application.FineAdjustmentRequests;
import lms.circulation.fine.application.FinePageResponse;
import lms.circulation.fine.application.FineRefundResponse;
import lms.circulation.fine.application.FineRefundUseCases;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Duyệt/từ chối/xem đề nghị hoàn khoản khi tìm lại sách đã báo mất. Đề nghị được lập ở
 * POST /internal/borrowings/found (BorrowingController/reportFound) vì cần dữ liệu lượt mượn và trạng thái bản sao.
 * Khóa quyền sinh tự động: FineRefundController/list, approve, reject. Người duyệt phải khác người đề nghị (409
 * APPROVER_MUST_DIFFER). Duyệt: catalog lỗi thì rollback toàn bộ, không có bút toán.
 */
@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/fines/refund-requests")
public class FineRefundController {

    private final FineRefundUseCases useCases;

    public FineRefundController(FineRefundUseCases useCases) {
        this.useCases = useCases;
    }

    @GetMapping
    public FinePageResponse<FineRefundResponse> list(FineRefundUseCases.Search query) {
        return useCases.list(query);
    }

    @PostMapping("/{id}/approve")
    public FineRefundResponse approve(@PathVariable UUID id,
                                      @Valid @RequestBody(required = false) FineAdjustmentRequests.Decision body,
                                      @AuthenticationPrincipal Jwt jwt) {
        return useCases.approve(id, body, FineAdjustmentController.userId(jwt));
    }

    @PostMapping("/{id}/reject")
    public FineRefundResponse reject(@PathVariable UUID id,
                                     @Valid @RequestBody(required = false) FineAdjustmentRequests.Decision body,
                                     @AuthenticationPrincipal Jwt jwt) {
        return useCases.reject(id, body, FineAdjustmentController.userId(jwt));
    }
}
