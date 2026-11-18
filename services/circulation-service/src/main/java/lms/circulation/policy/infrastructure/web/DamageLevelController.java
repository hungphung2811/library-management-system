package lms.circulation.policy.infrastructure.web;

import jakarta.validation.Valid;
import lms.circulation.policy.application.CreateDamageLevelRequest;
import lms.circulation.policy.application.CreateDamageLevelUseCase;
import lms.circulation.policy.application.DamageLevelListResponse;
import lms.circulation.policy.application.DamageLevelResponse;
import lms.circulation.policy.application.DeleteDamageLevelUseCase;
import lms.circulation.policy.application.UpdateDamageLevelRequest;
import lms.circulation.policy.application.UpdateDamageLevelUseCase;
import lms.circulation.policy.infrastructure.cache.CachedCompensationReader;
import lms.security.CurrentUser;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bậc hỏng do tenant định nghĩa. Khóa quyền sinh tự động: DamageLevelController/list, create, update, delete.
 */
@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/circulation/damage-levels")
public class DamageLevelController {

    private final CachedCompensationReader reader;
    private final CreateDamageLevelUseCase create;
    private final UpdateDamageLevelUseCase update;
    private final DeleteDamageLevelUseCase delete;

    public DamageLevelController(CachedCompensationReader reader, CreateDamageLevelUseCase create,
                                 UpdateDamageLevelUseCase update, DeleteDamageLevelUseCase delete) {
        this.reader = reader;
        this.create = create;
        this.update = update;
        this.delete = delete;
    }

    @GetMapping
    public DamageLevelListResponse list() {
        return reader.levels();
    }

    @PostMapping
    public ResponseEntity<DamageLevelResponse> create(@Valid @RequestBody CreateDamageLevelRequest request,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(create.execute(request, userId(jwt)));
    }

    @PutMapping("/{code}")
    public DamageLevelResponse update(@PathVariable String code, @Valid @RequestBody UpdateDamageLevelRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        return update.execute(code, request, userId(jwt));
    }

    /** Chỉ xóa được bậc chưa từng được dùng; bậc đã dùng trả 409 DAMAGE_LEVEL_IN_USE (hãy tắt bằng update). */
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        delete.execute(code);
        return ResponseEntity.noContent().build();
    }

    private static java.util.UUID userId(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
        }
        return CurrentUser.from(jwt).userId();
    }
}
