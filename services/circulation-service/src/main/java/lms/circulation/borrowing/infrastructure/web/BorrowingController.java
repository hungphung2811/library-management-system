package lms.circulation.borrowing.infrastructure.web;

import lms.circulation.borrowing.application.BorrowBookRequest;
import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.BorrowingPageResponse;
import lms.circulation.borrowing.application.SearchBorrowingsQuery;
import lms.circulation.borrowing.application.SearchBorrowingsUseCase;
import lms.circulation.borrowing.application.ReportLostRequest;
import lms.circulation.borrowing.application.ReportLostUseCase;
import lms.circulation.borrowing.application.ListBorrowingsUseCase;
import lms.circulation.borrowing.application.ReturnBookRequest;
import lms.circulation.borrowing.application.BorrowingMapper;
import lms.circulation.borrowing.application.BorrowBookUseCase;
import lms.circulation.borrowing.application.GetBorrowingUseCase;
import lms.circulation.borrowing.application.RenewBorrowingUseCase;
import lms.circulation.borrowing.application.ReturnBookUseCase;
import lms.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import lms.security.permission.AuthenticatedOnly;
import lms.security.permission.CrossTenantReadable;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/borrowings")
public class BorrowingController {

    private final BorrowBookUseCase borrowBook;
    private final ReturnBookUseCase returnBook;
    private final RenewBorrowingUseCase renewBorrowing;
    private final GetBorrowingUseCase getBorrowing;
    private final ReportLostUseCase reportLost;
    private final ListBorrowingsUseCase listBorrowings;
    private final SearchBorrowingsUseCase searchBorrowings;

    public BorrowingController(BorrowBookUseCase borrowBook, ReturnBookUseCase returnBook,
                               RenewBorrowingUseCase renewBorrowing, GetBorrowingUseCase getBorrowing,
                               ReportLostUseCase reportLost, ListBorrowingsUseCase listBorrowings,
                               SearchBorrowingsUseCase searchBorrowings) {
        this.searchBorrowings = searchBorrowings;
        this.reportLost = reportLost;
        this.listBorrowings = listBorrowings;
        this.borrowBook = borrowBook;
        this.returnBook = returnBook;
        this.renewBorrowing = renewBorrowing;
        this.getBorrowing = getBorrowing;
    }

    @PostMapping
    public ResponseEntity<BorrowingResponse> borrow(@Valid @RequestBody BorrowBookRequest request) {
        BorrowingResponse response = borrowBook.execute(BorrowingMapper.toCommand(request));
        return ResponseEntity.created(URI.create("/internal/borrowings/" + response.id())).body(response);
    }

    /** UC07: trả một bản sao, tìm lượt mượn đang mở theo bookCopyId hoặc barcode (condition: GOOD hoặc DAMAGED). */
    @PostMapping("/returns")
    public BorrowingResponse returnCopy(@Valid @RequestBody ReturnBookRequest request) {
        return returnBook.execute(BorrowingMapper.toCommand(null, request));
    }

    /** UC08: báo mất một bản sao đang mượn; lập phí bồi thường, không tính phí trễ. */
    @PostMapping("/losses")
    public BorrowingResponse reportLost(@Valid @RequestBody ReportLostRequest request) {
        return reportLost.execute(BorrowingMapper.toCommand(request));
    }

    /** UC05: lượt mượn của chính độc giả đang đăng nhập; userId lấy từ token. */
    @AuthenticatedOnly
    @GetMapping("/mine")
    public List<BorrowingResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
        }
        return listBorrowings.execute(CurrentUser.from(jwt).userId());
    }

    /** Thủ thư xem phiếu mượn của một độc giả. */
    @CrossTenantReadable
    @GetMapping
    public List<BorrowingResponse> byReader(@RequestParam UUID readerId) {
        return listBorrowings.execute(readerId);
    }

    /**
     * Danh sách phiếu mượn có phân trang cho màn hình quản lý (khóa BorrowingController/search). Mọi tham số tùy chọn;
     * size > 100 hoặc status lạ trả 400 VALIDATION_FAILED; fines của phần tử luôn rỗng. Platform admin không tenant
     * xem mọi tenant hoặc lọc bằng ?tenantId= (ADR-0008, interceptor xử lý).
     */
    @CrossTenantReadable
    @GetMapping("/search")
    public BorrowingPageResponse search(SearchBorrowingsQuery query) {
        return searchBorrowings.execute(query);
    }

    @PostMapping("/{id}/returns")
    public BorrowingResponse returnBook(@PathVariable UUID id, @Valid @RequestBody ReturnBookRequest request) {
        return returnBook.execute(BorrowingMapper.toCommand(id, request));
    }

    @PostMapping("/{id}/renewals")
    public BorrowingResponse renew(@PathVariable UUID id) {
        return renewBorrowing.execute(id);
    }

    /**
     * Phiếu mượn của chính người gọi (userId từ token). Phiếu không tồn tại, của người khác hoặc của tenant khác đều
     * trả 404 BORROWING_NOT_FOUND. Muốn xem phiếu của người khác dùng {@link #getAny}.
     */
    @AuthenticatedOnly
    @GetMapping("/{id}")
    public BorrowingResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
        }
        return getBorrowing.executeOwn(id, CurrentUser.from(jwt).userId());
    }

    /** Nhân viên xem phiếu mượn bất kỳ trong tenant (khóa BorrowingController/getAny, phải gán cho role). */
    @GetMapping("/{id}/detail")
    public BorrowingResponse getAny(@PathVariable UUID id) {
        return getBorrowing.executeAny(id);
    }
}
