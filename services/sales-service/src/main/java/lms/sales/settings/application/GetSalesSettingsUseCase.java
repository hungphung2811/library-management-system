package lms.sales.settings.application;

import java.util.UUID;
import lms.sales.settings.domain.SalesSettings;
import org.springframework.transaction.annotation.Transactional;

/** Cấu hình hiệu lực; chưa lưu thì trả mặc định (version 0). */
public class GetSalesSettingsUseCase {

    private final SalesSettingsPort port;

    public GetSalesSettingsUseCase(SalesSettingsPort port) {
        this.port = port;
    }

    @Transactional(readOnly = true)
    public SalesSettingsView execute(UUID tenantId) {
        return SalesSettingsView.from(port.find(tenantId).orElseGet(() -> SalesSettings.defaults(tenantId)));
    }
}
