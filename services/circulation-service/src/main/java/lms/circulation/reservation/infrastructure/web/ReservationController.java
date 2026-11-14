package lms.circulation.reservation.infrastructure.web;

import lms.circulation.reservation.application.CancelReservationUseCase;
import lms.circulation.reservation.application.ExpireOverdueReservationsUseCase;
import lms.circulation.reservation.application.ListMyReservationsUseCase;
import lms.circulation.reservation.application.ReservationResponse;
import lms.circulation.reservation.application.ReserveTitleRequest;
import lms.circulation.reservation.application.ReserveTitleUseCase;
import lms.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Độc giả chỉ thao tác trên đặt trước của chính mình: readerId luôn lấy từ token, không nhận từ body. */
@RestController
@RequestMapping("/internal/reservations")
public class ReservationController {

    private final ReserveTitleUseCase reserve;
    private final CancelReservationUseCase cancel;
    private final ListMyReservationsUseCase listMine;
    private final ExpireOverdueReservationsUseCase expire;

    public ReservationController(ReserveTitleUseCase reserve, CancelReservationUseCase cancel,
                                 ListMyReservationsUseCase listMine, ExpireOverdueReservationsUseCase expire) {
        this.reserve = reserve;
        this.cancel = cancel;
        this.listMine = listMine;
        this.expire = expire;
    }

    /** UC14 chạy thủ công (thủ thư/quản trị); bộ hẹn giờ vẫn chạy hằng ngày. */
    @PostMapping("/expirations")
    public java.util.Map<String, Integer> expireNow() {
        return java.util.Map.of("expired", expire.execute());
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody ReserveTitleRequest request) {
        ReservationResponse response = reserve.execute(readerId(jwt), request.titleId());
        return ResponseEntity.created(URI.create("/internal/reservations/" + response.id())).body(response);
    }

    @GetMapping
    public List<ReservationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return listMine.execute(readerId(jwt));
    }

    @DeleteMapping("/{id}")
    public ReservationResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return cancel.execute(readerId(jwt), id);
    }

    private static UUID readerId(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
        }
        return CurrentUser.from(jwt).userId();
    }
}
