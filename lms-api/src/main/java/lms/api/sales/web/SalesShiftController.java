package lms.api.sales.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lms.api.sales.client.SalesShiftClient;
import lms.api.sales.dto.SalesDtos.SalesPage;
import lms.api.sales.dto.SalesShiftDtos.ApproveVarianceRequest;
import lms.api.sales.dto.SalesShiftDtos.CloseShiftRequest;
import lms.api.sales.dto.SalesShiftDtos.MyShiftsQuery;
import lms.api.sales.dto.SalesShiftDtos.OpenShiftRequest;
import lms.api.sales.dto.SalesShiftDtos.ReopenShiftRequest;
import lms.api.sales.dto.SalesShiftDtos.ShiftDetailView;
import lms.api.sales.dto.SalesShiftDtos.ShiftSearchQuery;
import lms.api.sales.dto.SalesShiftDtos.ShiftSettingsView;
import lms.api.sales.dto.SalesShiftDtos.ShiftView;
import lms.api.sales.dto.SalesShiftDtos.UpdateShiftSettingsRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ca thu ngân (ADR-0030 gói B): chuyển nguyên sang /internal/sales/shifts/** và /internal/sales/shift-settings (kiểm
 * khóa, module, tenant và quyền sở hữu ca "của tôi" ở sales-service; lms-api chỉ xác thực, lỗi 4xx chuyển nguyên).
 * Nhóm {@code /shifts/me/**} là ca của chính người gọi; nhóm còn lại dành cho quản lý.
 */
@RestController
@RequestMapping("/api/sales")
public class SalesShiftController {

    private final SalesShiftClient shifts;

    public SalesShiftController(SalesShiftClient shifts) {
        this.shifts = shifts;
    }

    // ---- ca của tôi ----

    @PostMapping("/shifts")
    public ResponseEntity<ShiftView> open(@Valid @RequestBody OpenShiftRequest request) {
        ShiftView created = shifts.openShift(request);
        return ResponseEntity.created(URI.create("/api/sales/shifts/me/" + created.id())).body(created);
    }

    @GetMapping("/shifts/me/current")
    public ShiftDetailView current() {
        return shifts.currentShift();
    }

    @GetMapping("/shifts/me")
    public SalesPage<ShiftView> mine(MyShiftsQuery query) {
        return shifts.myShifts(query);
    }

    @GetMapping("/shifts/me/{id}")
    public ShiftDetailView getMine(@PathVariable UUID id) {
        return shifts.myShift(id);
    }

    @PostMapping("/shifts/me/current/closing")
    public ShiftView close(@Valid @RequestBody CloseShiftRequest request) {
        return shifts.closeCurrentShift(request);
    }

    // ---- quản lý ----

    @GetMapping("/shifts")
    public SalesPage<ShiftView> search(ShiftSearchQuery query) {
        return shifts.searchShifts(query);
    }

    @GetMapping("/shifts/{id}")
    public ShiftDetailView get(@PathVariable UUID id) {
        return shifts.getShift(id);
    }

    @PostMapping("/shifts/{id}/closing")
    public ShiftView closeOnBehalf(@PathVariable UUID id, @Valid @RequestBody CloseShiftRequest request) {
        return shifts.closeShiftOnBehalf(id, request);
    }

    @PostMapping("/shifts/{id}/reopening")
    public ShiftView reopen(@PathVariable UUID id, @Valid @RequestBody ReopenShiftRequest request) {
        return shifts.reopenShift(id, request);
    }

    /** Thân tùy chọn (ghi chú duyệt). */
    @PostMapping("/shifts/{id}/variance-approval")
    public ShiftView approveVariance(@PathVariable UUID id,
                                     @Valid @RequestBody(required = false) ApproveVarianceRequest request) {
        return shifts.approveShiftVariance(id, request);
    }

    // ---- cấu hình ----

    @GetMapping("/shift-settings")
    public ShiftSettingsView getSettings() {
        return shifts.getShiftSettings();
    }

    @PutMapping("/shift-settings")
    public ShiftSettingsView updateSettings(@Valid @RequestBody UpdateShiftSettingsRequest request) {
        return shifts.updateShiftSettings(request);
    }
}
