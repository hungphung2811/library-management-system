package lms.circulation.policy.application;

import java.math.BigDecimal;
import java.time.Instant;
import lms.circulation.policy.domain.DamageLevel;
import lms.circulation.policy.domain.DamageLevelSnapshot;

/** version 0 và updatedAt null ở hạt giống (tenant chưa cấu hình, chưa có trong DB). */
public record DamageLevelResponse(String code, String label, BigDecimal rate, int sortOrder, boolean active,
                                  long version, Instant updatedAt) {

    static DamageLevelResponse from(DamageLevelSnapshot s) {
        DamageLevel l = s.level();
        return new DamageLevelResponse(l.code(), l.label(), l.rate(), l.sortOrder(), l.active(), s.version(), s.updatedAt());
    }

    static DamageLevelResponse seed(DamageLevel l) {
        return new DamageLevelResponse(l.code(), l.label(), l.rate(), l.sortOrder(), l.active(), 0, null);
    }
}
