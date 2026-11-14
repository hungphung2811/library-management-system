package lms.api.circulation.web;

import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.ExpirationResult;
import lms.api.circulation.dto.ReservationView;
import lms.api.circulation.dto.ReserveTitleRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API cho FE: đặt trước sách. Độc giả lấy từ token (được chuyển tiếp), không nhận từ body. */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final CirculationClient circulation;

    public ReservationController(CirculationClient circulation) {
        this.circulation = circulation;
    }

    @PostMapping
    public ResponseEntity<ReservationView> reserve(@Valid @RequestBody ReserveTitleRequest request) {
        ReservationView created = circulation.reserve(request.titleId());
        return ResponseEntity.created(URI.create("/api/reservations/" + created.id())).body(created);
    }

    @GetMapping
    public List<ReservationView> mine() {
        return circulation.myReservations();
    }

    @DeleteMapping("/{id}")
    public ReservationView cancel(@PathVariable UUID id) {
        return circulation.cancelReservation(id);
    }

    /** UC14 chạy thủ công (thủ thư/quản trị). */
    @PostMapping("/expirations")
    public ExpirationResult expireNow() {
        return circulation.expireReservations();
    }
}
