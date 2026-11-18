package lms.circulation.policy.application;

import java.math.BigDecimal;
import java.util.List;
import lms.circulation.policy.domain.CompensationRuleException;
import lms.circulation.policy.domain.DamageLevel;
import lms.circulation.policy.domain.DamageLevelRepository;
import lms.circulation.policy.domain.DamageLevelSnapshot;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chọn bậc hỏng áp cho một khoản hỏng lúc trả sách (dùng bean gốc, không qua cache). Quy tắc: tenant chưa cấu hình bậc
 * nào dùng hạt giống MINOR/MAJOR; DAMAGED không kèm bậc thì LEGACY_DAMAGED (tỉ lệ hỏng cấu hình chung, mặc định 50%)
 * khi tenant chưa có bậc hoạt động, ngược lại bắt buộc chọn bậc (422 DAMAGE_LEVEL_REQUIRED); mã không tồn tại (422
 * DAMAGE_LEVEL_UNKNOWN) hoặc đã tắt (422 DAMAGE_LEVEL_INACTIVE) bị từ chối.
 */
public class ResolveDamageLevelUseCase {

    private final DamageLevelRepository repository;

    public ResolveDamageLevelUseCase(DamageLevelRepository repository) {
        this.repository = repository;
    }

    /** Bản không đọc DB: luôn là tenant chưa cấu hình (hạt giống + LEGACY_DAMAGED). Dùng cho test. */
    public static ResolveDamageLevelUseCase seedsOnly() {
        return new ResolveDamageLevelUseCase(null);
    }

    /**
     * legacy=true: dùng mã LEGACY_DAMAGED với tỉ lệ hỏng cấu hình chung (rate null ở đây, nơi gọi điền); ngược lại
     * code/label/rate là của bậc đã chọn.
     */
    public record Resolution(String code, String label, BigDecimal rate, boolean legacy) {

        static Resolution legacyDefault() {
            return new Resolution(DamageLevel.LEGACY_CODE, DamageLevel.LEGACY_LABEL, null, true);
        }

        static Resolution of(DamageLevel level) {
            return new Resolution(level.code(), level.label(), level.rate(), false);
        }
    }

    @Transactional(readOnly = true)
    public Resolution resolve(String requestedCode) {
        List<DamageLevelSnapshot> stored = repository == null ? List.of() : repository.findAll();
        boolean configured = !stored.isEmpty();
        List<DamageLevel> pool = configured ? stored.stream().map(DamageLevelSnapshot::level).toList() : DamageLevel.seeds();
        String code = requestedCode == null ? "" : requestedCode.strip();
        if (code.isEmpty()) {
            boolean anyActive = configured && pool.stream().anyMatch(DamageLevel::active);
            if (anyActive) {
                throw CompensationRuleException.invalid("DAMAGE_LEVEL_REQUIRED",
                        "Đơn vị đã cấu hình bậc hỏng: phải chọn damageLevelCode khi trả sách hỏng");
            }
            return Resolution.legacyDefault();
        }
        DamageLevel level = pool.stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow(
                () -> CompensationRuleException.invalid("DAMAGE_LEVEL_UNKNOWN", "Không có bậc hỏng: " + code));
        if (!level.active()) {
            throw CompensationRuleException.invalid("DAMAGE_LEVEL_INACTIVE", "Bậc hỏng đã tắt, không chọn được: " + code);
        }
        return Resolution.of(level);
    }
}
