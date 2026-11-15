package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.BorrowRule;
import lms.circulation.borrowing.domain.BorrowRuleRepository;
import lms.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo quy tắc mượn mặc định cho tenant hiện tại (tenant mới tạo). Idempotent: loại độc giả đã có quy tắc thì giữ
 * nguyên, nên event giao lại không ghi đè quy tắc quản trị viên đã sửa. Bắt buộc có tenant trong ngữ cảnh; người gọi
 * phải đặt tenant TRƯỚC khi mở transaction (biến RLS chốt lúc mượn kết nối).
 */
public class ProvisionDefaultBorrowRulesUseCase {

    private final BorrowRuleRepository rules;

    public ProvisionDefaultBorrowRulesUseCase(BorrowRuleRepository rules) {
        this.rules = rules;
    }

    /** @return số quy tắc vừa thêm. */
    @Transactional
    public int execute() {
        TenantContext.require();
        int added = 0;
        for (BorrowRule rule : BorrowRule.defaults()) {
            if (rules.addIfAbsent(rule)) added++;
        }
        return added;
    }
}
