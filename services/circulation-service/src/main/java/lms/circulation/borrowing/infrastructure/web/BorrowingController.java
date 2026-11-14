package lms.circulation.borrowing.infrastructure.web;

import lms.circulation.borrowing.application.BorrowBookRequest;
import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.ReturnBookRequest;
import lms.circulation.borrowing.application.BorrowingMapper;
import lms.circulation.borrowing.application.BorrowBookUseCase;
import lms.circulation.borrowing.application.GetBorrowingUseCase;
import lms.circulation.borrowing.application.RenewBorrowingUseCase;
import lms.circulation.borrowing.application.ReturnBookUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/borrowings")
public class BorrowingController {

    private final BorrowBookUseCase borrowBook;
    private final ReturnBookUseCase returnBook;
    private final RenewBorrowingUseCase renewBorrowing;
    private final GetBorrowingUseCase getBorrowing;

    public BorrowingController(BorrowBookUseCase borrowBook, ReturnBookUseCase returnBook,
                               RenewBorrowingUseCase renewBorrowing, GetBorrowingUseCase getBorrowing) {
        this.borrowBook = borrowBook;
        this.returnBook = returnBook;
        this.renewBorrowing = renewBorrowing;
        this.getBorrowing = getBorrowing;
    }

    @PostMapping
    public ResponseEntity<BorrowingResponse> borrow(@Valid @RequestBody BorrowBookRequest request) {
        BorrowingResponse response = borrowBook.execute(BorrowingMapper.toCommand(request));
        return ResponseEntity.created(URI.create("/api/borrowings/" + response.id())).body(response);
    }

    @PostMapping("/{id}/returns")
    public BorrowingResponse returnBook(@PathVariable UUID id, @Valid @RequestBody ReturnBookRequest request) {
        return returnBook.execute(BorrowingMapper.toCommand(id, request));
    }

    @PostMapping("/{id}/renewals")
    public BorrowingResponse renew(@PathVariable UUID id) {
        return renewBorrowing.execute(id);
    }

    @GetMapping("/{id}")
    public BorrowingResponse get(@PathVariable UUID id) {
        return getBorrowing.execute(id);
    }
}
