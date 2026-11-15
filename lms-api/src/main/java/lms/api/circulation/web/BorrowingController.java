package lms.api.circulation.web;

import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.BorrowBookRequest;
import lms.api.circulation.dto.BorrowingView;
import lms.api.circulation.dto.ReportLostRequest;
import lms.api.circulation.dto.ReturnBookRequest;
import lms.api.circulation.dto.ReturnCopyRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestParam;
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
    public List<BorrowingView> byReader(@RequestParam UUID readerId) {
        return circulation.borrowingsOfReader(readerId);
    }

    @PostMapping("/{id}/returns")
    public BorrowingView returnBook(@PathVariable UUID id, @Valid @RequestBody ReturnBookRequest request) {
        return circulation.returnBook(id, request.bookCopyId());
    }

    @PostMapping("/{id}/renewals")
    public BorrowingView renew(@PathVariable UUID id) {
        return circulation.renew(id);
    }

    @GetMapping("/{id}")
    public BorrowingView get(@PathVariable UUID id) {
        return circulation.getBorrowing(id);
    }
}
