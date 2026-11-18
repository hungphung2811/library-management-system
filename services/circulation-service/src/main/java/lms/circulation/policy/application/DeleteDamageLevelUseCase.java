package lms.circulation.policy.application;

import lms.circulation.policy.domain.CompensationRuleException;
import lms.circulation.policy.domain.DamageLevelRepository;
import org.springframework.transaction.annotation.Transactional;

/** Xóa bậc hỏng chưa từng được dùng; bậc đã được dùng không xóa được (409 DAMAGE_LEVEL_IN_USE), chỉ tắt (active=false). */
public class DeleteDamageLevelUseCase {

    private final DamageLevelRepository repository;
    private final DamageLevelUsagePort usage;

    public DeleteDamageLevelUseCase(DamageLevelRepository repository, DamageLevelUsagePort usage) {
        this.repository = repository;
        this.usage = usage;
    }

    @Transactional
    public void execute(String code) {
        repository.findByCode(code).orElseThrow(() -> CompensationRuleException.notFound(
                "DAMAGE_LEVEL_NOT_FOUND", "Không tìm thấy bậc hỏng: " + code));
        if (usage.isUsed(code)) {
            throw CompensationRuleException.conflict("DAMAGE_LEVEL_IN_USE",
                    "Bậc hỏng đã được dùng ở khoản phạt, không xóa được; hãy tắt (active=false)");
        }
        repository.delete(code);
    }
}
