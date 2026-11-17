package lms.api.circulation.web;

import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.CollectFinesRequest;
import lms.api.circulation.dto.CollectFinesResult;
import lms.api.circulation.dto.ReaderFinesView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API cho FE: xem và thu phí phạt. Quyền do circulation-service kiểm. */
@RestController
@RequestMapping("/api/fines")
public class FineController {

    private final CirculationClient circulation;

    public FineController(CirculationClient circulation) {
        this.circulation = circulation;
    }

    @GetMapping("/mine")
    public ReaderFinesView mine() {
        return circulation.myFines();
    }

    @GetMapping
    public ReaderFinesView byReader(@Valid BorrowingController.ByReaderQuery query) {
        return circulation.finesOfReader(query.readerId(), query.tenantId());
    }

    @PostMapping("/payments")
    public CollectFinesResult collect(@Valid @RequestBody CollectFinesRequest request) {
        return circulation.collectFines(request.readerId());
    }
}
