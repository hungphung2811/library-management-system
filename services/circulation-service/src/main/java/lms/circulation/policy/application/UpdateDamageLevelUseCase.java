package lms.circulation.policy.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lms.circulation.policy.domain.CompensationRuleException;
import lms.circulation.policy.domain.DamageLevel;
import lms.circulation.policy.domain.DamageLevelRepository;
import lms.circulation.policy.domain.DamageLevelSnapshot;
import lms.circulation.policy.domain.PolicyVersionConflictException;
import org.springframework.transaction.annotation.Transactional;

/** Sửa bậc hỏng. Chỉ có tác dụng với khoản phạt lập sau đó: khoản cũ giữ snapshot mã/nhãn/tỉ lệ lúc lập. */
public class UpdateDamageLevelUseCase {

    private final DamageLevelRepository repository;
    private final Clock clock;

    public UpdateDamageLevelUseCase(DamageLevelRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public DamageLevelResponse execute(String code, UpdateDamageLevelRequest request, UUID updatedBy) {
        if (request.code() != null && !request.code().strip().equals(code)) {
            throw CompensationRuleException.invalid("DAMAGE_LEVEL_CODE_IMMUTABLE", "Không đổi được mã bậc hỏng sau khi tạo");
        }
        DamageLevelSnapshot current = repository.findByCode(code).orElseThrow(() -> CompensationRuleException.notFound(
                "DAMAGE_LEVEL_NOT_FOUND", "Không tìm thấy bậc hỏng: " + code));
        long expected = request.version() == null ? current.version() : request.version();
        if (expected != current.version()) {
            throw new PolicyVersionConflictException(
                    "Bậc hỏng đã được người khác cập nhật (version hiện tại " + current.version() + "), hãy tải lại rồi sửa");
        }
        DamageLevel level = new DamageLevel(code, request.label(), request.rate(), request.sortOrder(), request.active());
        return DamageLevelResponse.from(repository.update(level, expected, updatedBy, Instant.now(clock)));
    }
}
