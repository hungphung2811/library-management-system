package lms.api.circulation.web;

import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.BorrowBookRequest;
import lms.api.circulation.dto.BorrowingPageView;
import lms.api.circulation.dto.BorrowingSearchQuery;
import lms.api.circulation.dto.BorrowingView;
import lms.api.circulation.dto.ReportLostRequest;
import lms.api.circulation.dto.ReturnBookRequest;
import lms.api.circulation.dto.ReturnCopyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API cho FE: mượn, trả, gia hạn và xem phiếu mượn. Quyền do circulation-service kiểm. */
@RestController
@RequestMapping("/api/borrowings")
public class BorrowingController {

    /** tenantId chỉ có tác dụng với platform admin không tenant (ADR-0008); chuyển nguyên xuống service. */
    public record ByReaderQuery(@NotNull UUID readerId, String tenantId) {
    }

    private final CirculationClient circulation;

    public BorrowingController(CirculationClient circulation) {
        this.circulation = circulation;
    }

    @PostMapping
    public ResponseEntity<BorrowingView> borrow(@Valid @RequestBody BorrowBookRequest request) {
        BorrowingView created = circulation.borrow(request);
        return ResponseEntity.created(URI.create("/api/borrowings/" + created.id())).body(created);
    }

    /** Trả theo bookCopyId hoặc barcode, không cần biết id phiếu mượn. */
    @PostMapping("/returns")
    public BorrowingView returnCopy(@Valid @RequestBody ReturnCopyRequest request) {
        return circulation.returnCopy(request);
    }

    @PostMapping("/losses")
    public BorrowingView reportLost(@Valid @RequestBody ReportLostRequest request) {
        return circulation.reportLost(request);
    }

    /** Phiếu mượn của chính người đăng nhập. */
    @GetMapping("/mine")
    public List<BorrowingView> mine() {
        return circulation.myBorrowings();
    }

    /** Thủ thư xem phiếu mượn của một độc giả. */
    @GetMapping
    public List<BorrowingView> byReader(@Valid ByReaderQuery query) {
        return circulation.borrowingsOfReader(query.readerId(), query.tenantId());
    }

    /** Danh sách phiếu mượn có phân trang cho màn hình quản lý; mọi query tùy chọn, chuyển nguyên xuống service. */
    @GetMapping("/search")
    public BorrowingPageView search(BorrowingSearchQuery query) {
        return circulation.searchBorrowings(query);
    }

    @PostMapping("/{id}/returns")
    public BorrowingView returnBook(@PathVariable UUID id, @Valid @RequestBody ReturnBookRequest request) {
        return circulation.returnBook(id, request);
    }

    /** Ghi nhận tìm lại sách đã báo mất: tạo đề nghị hoàn khoản chờ người khác duyệt (khóa BorrowingController/reportFound). */
    @PostMapping("/found")
    public ResponseEntity<lms.api.circulation.dto.CompensationViews.Refund> reportFound(
            @Valid @RequestBody lms.api.circulation.dto.CompensationViews.ReportFound request) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(circulation.reportFound(request));
    }

    @PostMapping("/{id}/renewals")
    public BorrowingView renew(@PathVariable UUID id) {
        return circulation.renew(id);
    }

    /** Phiếu của chính người đăng nhập; phiếu của người khác trả 404 BORROWING_NOT_FOUND. */
    @GetMapping("/{id}")
    public BorrowingView get(@PathVariable UUID id) {
        return circulation.getBorrowing(id);
    }

    /** Nhân viên xem phiếu bất kỳ trong tenant (khóa BorrowingController/getAny do circulation-service kiểm). */
    @GetMapping("/{id}/detail")
    public BorrowingView detail(@PathVariable UUID id) {
        return circulation.getBorrowingDetail(id);
    }
}
