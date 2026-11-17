package lms.sales.stock.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.Supplier;
import org.springframework.transaction.annotation.Transactional;

/** Tạo / sửa / tìm nhà cung cấp. */
public class SupplierUseCases {

    public record SupplierData(String name, String phone, String email, String address, String note) {
    }

    private final SupplierRepository suppliers;
    private final SalesAuditPort audit;
    private final Clock clock;

    public SupplierUseCases(SupplierRepository suppliers, SalesAuditPort audit, Clock clock) {
        this.suppliers = suppliers;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Supplier create(UUID tenantId, UUID userId, SupplierData d) {
        Supplier s = Supplier.create(tenantId, d.name(), d.phone(), d.email(), d.address(), d.note());
        if (suppliers.existsByName(tenantId, s.name(), null)) {
            throw new SalesException(ErrorCode.SUPPLIER_EXISTS, "Nhà cung cấp đã tồn tại");
        }
        Supplier saved = suppliers.save(s);
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.SUPPLIER_CREATED, "Supplier",
                saved.id(), userId, clock.instant(), Map.of()));
        return saved;
    }

    /** {@code active} null = giữ nguyên trạng thái hiện tại. */
    @Transactional
    public Supplier update(UUID tenantId, UUID userId, UUID id, SupplierData d, Boolean active) {
        Supplier s = suppliers.findById(tenantId, id)
                .orElseThrow(() -> new SalesException(ErrorCode.SUPPLIER_NOT_FOUND, "Không có nhà cung cấp"));
        s.update(d.name(), d.phone(), d.email(), d.address(), d.note(), active == null ? s.active() : active);
        if (suppliers.existsByName(tenantId, s.name(), s.id())) {
            throw new SalesException(ErrorCode.SUPPLIER_EXISTS, "Nhà cung cấp đã tồn tại");
        }
        Supplier saved = suppliers.save(s);
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.SUPPLIER_UPDATED, "Supplier",
                saved.id(), userId, clock.instant(), Map.of("active", saved.active())));
        return saved;
    }

    @Transactional(readOnly = true)
    public PageResult<Supplier> search(UUID tenantId, String keyword, int page, int size) {
        return suppliers.search(tenantId, keyword, page, size);
    }
}
