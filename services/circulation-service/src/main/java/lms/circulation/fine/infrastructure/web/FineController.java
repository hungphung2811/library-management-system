package lms.circulation.fine.infrastructure.web;

import lms.circulation.fine.application.CollectFinesRequest;
import lms.circulation.fine.application.CollectFinesUseCase;
import lms.circulation.fine.application.GetReaderFinesUseCase;
import lms.security.CurrentUser;
import lms.security.permission.AuthenticatedOnly;
import lms.security.permission.CrossTenantReadable;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/fines")
public class FineController {

    private final GetReaderFinesUseCase getFines;
    private final CollectFinesUseCase collectFines;

    public FineController(GetReaderFinesUseCase getFines, CollectFinesUseCase collectFines) {
        this.getFines = getFines;
        this.collectFines = collectFines;
    }

    /** UC05: độc giả xem phí phạt của chính mình; userId lấy từ token. */
    @AuthenticatedOnly
    @GetMapping("/mine")
    public GetReaderFinesUseCase.ReaderFinesResponse mine(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
        }
        return getFines.execute(CurrentUser.from(jwt).userId());
    }

    /** Thủ thư xem phí phạt của một độc giả (UC06 bước 2b, UC09). */
    @CrossTenantReadable
    @GetMapping
    public GetReaderFinesUseCase.ReaderFinesResponse byReader(@RequestParam UUID readerId) {
        return getFines.execute(readerId);
    }

    /** UC09: thu mọi khoản phạt chưa thanh toán của độc giả. */
    @PostMapping("/payments")
    public CollectFinesUseCase.CollectFinesResponse collect(@Valid @RequestBody CollectFinesRequest request) {
        return collectFines.execute(request.readerId());
    }
}
