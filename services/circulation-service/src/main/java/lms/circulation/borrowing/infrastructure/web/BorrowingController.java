package lms.circulation.borrowing.infrastructure.web;

import lms.circulation.borrowing.application.BorrowBookRequest;
import lms.circulation.borrowing.application.BorrowingResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/borrowings")
public class BorrowingController {

    private final BorrowBookUseCase borrowBook;
    private final ReturnBookUseCase returnBook;
    private final RenewBorrowingUseCase renewBorrowing;
    private final GetBorrowingUseCase getBorrowing;
    private final ReportLostUseCase reportLost;
    private final ListBorrowingsUseCase listBorrowings;

    public BorrowingController(BorrowBookUseCase borrowBook, ReturnBookUseCase returnBook,
                               RenewBorrowingUseCase renewBorrowing, GetBorrowingUseCase getBorrowing,
                               ReportLostUseCase reportLost, ListBorrowingsUseCase listBorrowings) {
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
    @GetMapping
    public List<BorrowingResponse> byReader(@RequestParam UUID readerId) {
        return listBorrowings.execute(readerId);
    }

    @PostMapping("/{id}/returns")
    public BorrowingResponse returnBook(@PathVariable UUID id, @Valid @RequestBody ReturnBookRequest request) {
        return returnBook.execute(BorrowingMapper.toCommand(id, request));
    }

    @PostMapping("/{id}/renewals")
    public BorrowingResponse renew(@PathVariable UUID id) {
        return renewBorrowing.execute(id);
    }

    @AuthenticatedOnly
    @GetMapping("/{id}")
    public BorrowingResponse get(@PathVariable UUID id) {
        return getBorrowing.execute(id);
    }
}
