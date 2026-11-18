package lms.circulation.policy.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lms.circulation.policy.domain.CompensationRuleException;
import lms.circulation.policy.domain.DamageLevel;
import lms.circulation.policy.domain.DamageLevelRepository;
import org.springframework.transaction.annotation.Transactional;

/** Tạo bậc hỏng cho tenant hiện tại: mã không trùng trong tenant, tối đa {@link DamageLevel#MAX_LEVELS_PER_TENANT} bậc. */
public class CreateDamageLevelUseCase {

    private final DamageLevelRepository repository;
    private final Clock clock;

    public CreateDamageLevelUseCase(DamageLevelRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public DamageLevelResponse execute(CreateDamageLevelRequest request, UUID updatedBy) {
        DamageLevel level = new DamageLevel(request.code() == null ? null : request.code().strip(), request.label(),
                request.rate(), request.sortOrder() == null ? 0 : request.sortOrder(),
                request.active() == null || request.active());
        if (repository.findByCode(level.code()).isPresent()) {
            throw CompensationRuleException.conflict("DAMAGE_LEVEL_CODE_EXISTS",
                    "Mã bậc hỏng đã tồn tại trong đơn vị: " + level.code());
        }
        if (repository.count() >= DamageLevel.MAX_LEVELS_PER_TENANT) {
            throw CompensationRuleException.conflict("DAMAGE_LEVEL_LIMIT_REACHED",
                    "Mỗi đơn vị tối đa " + DamageLevel.MAX_LEVELS_PER_TENANT + " bậc hỏng");
        }
        return DamageLevelResponse.from(repository.create(level, updatedBy, Instant.now(clock)));
    }
}
