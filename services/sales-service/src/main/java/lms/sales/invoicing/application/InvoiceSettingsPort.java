package lms.sales.invoicing.application;

import java.util.Optional;
import java.util.UUID;
import lms.sales.invoicing.domain.InvoiceSettings;

/** Cấu hình hóa đơn theo tenant. Lần lưu đầu (version 0) chèn; sau đó cập nhật có điều kiện version (409 SETTINGS_VERSION_CONFLICT). */
public interface InvoiceSettingsPort {

    Optional<InvoiceSettings> find(UUID tenantId);

    InvoiceSettings save(InvoiceSettings settings);
}
