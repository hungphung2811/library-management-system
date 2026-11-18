package lms.api.circulation.web;

import jakarta.validation.Valid;
import java.util.UUID;
import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.CompensationViews;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API cho FE: chính sách đền bù, bậc hỏng, miễn/giảm khoản phạt, hoàn khoản. Chuyển nguyên xuống circulation-service
 * (kiểm quyền theo khóa Controller/method của service, và người duyệt khác người đề nghị).
 */
@RestController
@RequestMapping("/api")
public class CompensationController {

    private final CirculationClient circulation;

    public CompensationController(CirculationClient circulation) {
        this.circulation = circulation;
    }

    @GetMapping("/circulation/compensation-policy")
    public CompensationViews.CompensationPolicy getPolicy() {
        return circulation.getCompensationPolicy();
    }

    @PutMapping("/circulation/compensation-policy")
    public CompensationViews.CompensationPolicy updatePolicy(
            @Valid @RequestBody CompensationViews.UpdateCompensationPolicy request) {
        return circulation.updateCompensationPolicy(request);
    }

    @GetMapping("/circulation/damage-levels")
    public CompensationViews.DamageLevelList listLevels() {
        return circulation.listDamageLevels();
    }

    @PostMapping("/circulation/damage-levels")
    public ResponseEntity<CompensationViews.DamageLevel> createLevel(
            @Valid @RequestBody CompensationViews.CreateDamageLevel request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(circulation.createDamageLevel(request));
    }

    @PutMapping("/circulation/damage-levels/{code}")
    public CompensationViews.DamageLevel updateLevel(@PathVariable String code,
                                                     @Valid @RequestBody CompensationViews.UpdateDamageLevel request) {
        return circulation.updateDamageLevel(code, request);
    }

    @DeleteMapping("/circulation/damage-levels/{code}")
    public ResponseEntity<Void> deleteLevel(@PathVariable String code) {
        circulation.deleteDamageLevel(code);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/fines/{id}/adjustment-requests")
    public ResponseEntity<CompensationViews.Adjustment> requestAdjustment(
            @PathVariable UUID id, @Valid @RequestBody CompensationViews.CreateAdjustment request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(circulation.requestAdjustment(id, request));
    }

    @GetMapping("/fines/adjustment-requests")
    public CompensationViews.Page<CompensationViews.Adjustment> listAdjustments(CompensationViews.AdjustmentSearch query) {
        return circulation.listAdjustments(query);
    }

    @PostMapping("/fines/adjustment-requests/{requestId}/approve")
    public CompensationViews.Adjustment approveAdjustment(
            @PathVariable UUID requestId, @Valid @RequestBody(required = false) CompensationViews.Decision body) {
        return circulation.approveAdjustment(requestId, body);
    }

    @PostMapping("/fines/adjustment-requests/{requestId}/reject")
    public CompensationViews.Adjustment rejectAdjustment(
            @PathVariable UUID requestId, @Valid @RequestBody(required = false) CompensationViews.Decision body) {
        return circulation.rejectAdjustment(requestId, body);
    }

    @GetMapping("/fines/refund-requests")
    public CompensationViews.Page<CompensationViews.Refund> listRefunds(CompensationViews.RefundSearch query) {
        return circulation.listRefunds(query);
    }

    @PostMapping("/fines/refund-requests/{id}/approve")
    public CompensationViews.Refund approveRefund(
            @PathVariable UUID id, @Valid @RequestBody(required = false) CompensationViews.Decision body) {
        return circulation.approveRefund(id, body);
    }

    @PostMapping("/fines/refund-requests/{id}/reject")
    public CompensationViews.Refund rejectRefund(
            @PathVariable UUID id, @Valid @RequestBody(required = false) CompensationViews.Decision body) {
        return circulation.rejectRefund(id, body);
    }
}
