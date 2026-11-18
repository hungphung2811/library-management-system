package lms.api.circulation.client;

import lms.api.circulation.dto.BorrowBookRequest;
import lms.api.circulation.dto.BorrowingView;
import lms.api.circulation.dto.BorrowingPageView;
import lms.api.circulation.dto.BorrowingSearchQuery;
import lms.api.circulation.dto.CirculationPolicyView;
import lms.api.circulation.dto.UpdateCirculationPolicyRequest;
import lms.api.circulation.dto.CollectFinesResult;
import lms.api.circulation.dto.ExpirationResult;
import lms.api.circulation.dto.ReaderFinesView;
import lms.api.circulation.dto.ReportLostRequest;
import lms.api.circulation.dto.ReservationView;
import lms.api.circulation.dto.ReturnCopyRequest;
import lms.api.circulation.dto.CompensationViews;
import java.util.List;
import java.util.UUID;

/** Cổng sang circulation-service (API nội bộ). Lỗi 4xx của service được ném dưới dạng RestClientResponseException. */
public interface CirculationClient {

    BorrowingView borrow(BorrowBookRequest request);

    BorrowingView returnBook(UUID borrowingId, lms.api.circulation.dto.ReturnBookRequest request);

    BorrowingView renew(UUID borrowingId);

    /** Phiếu của chính người gọi; service trả 404 nếu không phải của họ. */
    BorrowingView getBorrowing(UUID borrowingId);

    /** Phiếu bất kỳ trong tenant, cho nhân viên có khóa BorrowingController/getAny. */
    BorrowingView getBorrowingDetail(UUID borrowingId);

    BorrowingView returnCopy(ReturnCopyRequest request);

    BorrowingView reportLost(ReportLostRequest request);

    List<BorrowingView> myBorrowings();

    List<BorrowingView> borrowingsOfReader(UUID readerId, String tenantId);

    BorrowingPageView searchBorrowings(BorrowingSearchQuery query);

    ReaderFinesView myFines();

    ReaderFinesView finesOfReader(UUID readerId, String tenantId);

    CollectFinesResult collectFines(UUID readerId);

    ReservationView reserve(UUID titleId);

    List<ReservationView> myReservations();

    ReservationView cancelReservation(UUID reservationId);

    ExpirationResult expireReservations();

    CirculationPolicyView getPolicy();

    CirculationPolicyView updatePolicy(UpdateCirculationPolicyRequest request);

    CompensationViews.CompensationPolicy getCompensationPolicy();

    CompensationViews.CompensationPolicy updateCompensationPolicy(CompensationViews.UpdateCompensationPolicy request);

    CompensationViews.DamageLevelList listDamageLevels();

    CompensationViews.DamageLevel createDamageLevel(CompensationViews.CreateDamageLevel request);

    CompensationViews.DamageLevel updateDamageLevel(String code, CompensationViews.UpdateDamageLevel request);

    void deleteDamageLevel(String code);

    CompensationViews.Adjustment requestAdjustment(UUID fineId, CompensationViews.CreateAdjustment request);

    CompensationViews.Page<CompensationViews.Adjustment> listAdjustments(CompensationViews.AdjustmentSearch query);

    CompensationViews.Adjustment approveAdjustment(UUID requestId, CompensationViews.Decision decision);

    CompensationViews.Adjustment rejectAdjustment(UUID requestId, CompensationViews.Decision decision);

    CompensationViews.Refund reportFound(CompensationViews.ReportFound request);

    CompensationViews.Page<CompensationViews.Refund> listRefunds(CompensationViews.RefundSearch query);

    CompensationViews.Refund approveRefund(UUID id, CompensationViews.Decision decision);

    CompensationViews.Refund rejectRefund(UUID id, CompensationViews.Decision decision);
}
