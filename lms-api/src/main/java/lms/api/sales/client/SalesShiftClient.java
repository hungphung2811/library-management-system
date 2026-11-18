package lms.api.sales.client;

import java.util.UUID;
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

/**
 * Cổng sang /internal/sales/shifts/** và /internal/sales/shift-settings của sales-service (ADR-0030 gói B). Khóa quyền,
 * module và quyền sở hữu ca ("của tôi") kiểm ở sales-service; client này chỉ chuyển tiếp, lỗi 4xx chuyển nguyên.
 */
public interface SalesShiftClient {

    ShiftView openShift(OpenShiftRequest request);

    ShiftDetailView currentShift();

    SalesPage<ShiftView> myShifts(MyShiftsQuery query);

    ShiftDetailView myShift(UUID id);

    ShiftView closeCurrentShift(CloseShiftRequest request);

    SalesPage<ShiftView> searchShifts(ShiftSearchQuery query);

    ShiftDetailView getShift(UUID id);

    ShiftView closeShiftOnBehalf(UUID id, CloseShiftRequest request);

    ShiftView reopenShift(UUID id, ReopenShiftRequest request);

    /** Thân có thể null (ghi chú tùy chọn). */
    ShiftView approveShiftVariance(UUID id, ApproveVarianceRequest request);

    ShiftSettingsView getShiftSettings();

    ShiftSettingsView updateShiftSettings(UpdateShiftSettingsRequest request);
}
