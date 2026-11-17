package lms.identity.contract.infrastructure.web;

import lms.identity.contract.application.PlatformOverviewUseCase;
import lms.identity.contract.application.PlatformOverviewUseCase.PlatformOverviewResponse;
import lms.security.permission.PlatformAdminOnly;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tổng quan nền tảng (ADR-0018): chỉ số tenant/hợp đồng, không có số liệu nghiệp vụ. Không dùng khóa
 * Controller/method (platformAdmin). Đặt ở {@code /api/platform/**} theo mẫu các API platform để lms-api chuyển nguyên.
 */
@RestController
@RequestMapping("/api/platform/dashboard")
@PlatformAdminOnly
public class PlatformDashboardController {

    private final PlatformOverviewUseCase overview;

    public PlatformDashboardController(PlatformOverviewUseCase overview) {
        this.overview = overview;
    }

    /** Chỉ một tham số query nên không cần record. */
    @GetMapping("/overview")
    public PlatformOverviewResponse overview(@RequestParam(required = false) Integer withinDays) {
        return overview.execute(withinDays);
    }
}
