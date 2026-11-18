package lms.circulation.policy.application;

import java.util.List;
import lms.circulation.policy.domain.DamageLevel;
import lms.circulation.policy.domain.DamageLevelRepository;
import lms.circulation.policy.domain.DamageLevelSnapshot;
import org.springframework.transaction.annotation.Transactional;

/** Danh sách bậc hỏng của tenant hiện tại (cả bậc đã tắt, để hiển thị ở khoản cũ); chưa cấu hình thì trả hạt giống. */
public class ListDamageLevelsUseCase {

    private final DamageLevelRepository repository;

    public ListDamageLevelsUseCase(DamageLevelRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public DamageLevelListResponse execute() {
        List<DamageLevelSnapshot> levels = repository.findAll();
        if (levels.isEmpty()) {
            return new DamageLevelListResponse(false, DamageLevel.MAX_LEVELS_PER_TENANT,
                    DamageLevel.seeds().stream().map(DamageLevelResponse::seed).toList());
        }
        return new DamageLevelListResponse(true, DamageLevel.MAX_LEVELS_PER_TENANT,
                levels.stream().map(DamageLevelResponse::from).toList());
    }
}
