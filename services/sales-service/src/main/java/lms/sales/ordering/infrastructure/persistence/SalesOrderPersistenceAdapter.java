package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.application.SalesOrderRepository;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.OrderChannel;
import lms.sales.ordering.domain.OrderLine;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentRecord;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.ordering.domain.Recipient;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.DuplicateClientRequestException;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.shared.infrastructure.persistence.Like;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class SalesOrderPersistenceAdapter implements SalesOrderRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public SalesOrder save(SalesOrder o) {
        try {
            SalesOrderJpaEntity entity = em.find(SalesOrderJpaEntity.class, o.id());
            if (entity == null) {
                insert(o);
                em.flush();
                return load(o.id()).orElseThrow();
            }
            if (entity.getVersion() != o.version()) {
                throw stateConflict();
            }
            entity.apply(o);
            syncLines(o);
            syncPayments(o);
            em.flush();
            return load(o.id()).orElseThrow();
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_sales_orders_tenant_request")) {
                throw new DuplicateClientRequestException();
            }
            if (PersistenceErrors.isUniqueViolation(e, "uq_payment_records_order")) {
                throw new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Đơn đã được đánh dấu đã thu");
            }
            if (PersistenceErrors.isUniqueViolation(e, "uq_sales_orders_exchange")) {
                throw new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Phiếu trả đã được dùng để đổi hàng");
            }
            if (PersistenceErrors.isOptimisticLock(e)) {
                throw stateConflict();
            }
            throw e;
        }
    }

    private static SalesException stateConflict() {
        return new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Đơn hàng đã bị thay đổi bởi thao tác khác");
    }

    private void insert(SalesOrder o) {
        em.persist(new SalesOrderJpaEntity(o));
        for (OrderLine l : o.lines()) {
            em.persist(new SalesOrderLineJpaEntity(l.id(), o.id(), l.lineNo(), l.titleId(), l.titleName(), l.isbn(),
                    l.quantity(), l.unitPrice().amount(), l.lineDiscount().amount(),
                    l.allocatedOrderDiscount().amount(), l.amount().amount(), l.vatAmount().amount(),
                    l.lineTotal().amount()));
        }
        for (PaymentRecord p : o.payments()) {
            em.persist(new PaymentRecordJpaEntity(p.id(), o.id(), p.amount().amount(), p.method().name(),
                    p.reference(), p.recordedBy(), p.recordedAt()));
        }
    }

    /** Giảm giá đơn online tính lại các dòng: cập nhật cột tiền của dòng theo id. */
    private void syncLines(SalesOrder o) {
        Map<UUID, SalesOrderLineJpaEntity> byId = new HashMap<>();
        em.createQuery("select l from SalesOrderLineJpaEntity l where l.orderId = :o", SalesOrderLineJpaEntity.class)
                .setParameter("o", o.id()).getResultList().forEach(l -> byId.put(l.getId(), l));
        for (OrderLine l : o.lines()) {
            SalesOrderLineJpaEntity e = byId.get(l.id());
            if (e != null) {
                e.applyAmounts(l.lineDiscount().amount(), l.allocatedOrderDiscount().amount(), l.amount().amount(),
                        l.vatAmount().amount(), l.lineTotal().amount());
            }
        }
    }

    /** Bản ghi thu mới (đánh dấu đã thu) chèn thêm; bản ghi cũ bất biến. */
    private void syncPayments(SalesOrder o) {
        List<UUID> existing = em.createQuery("select p.id from PaymentRecordJpaEntity p where p.orderId = :o",
                UUID.class).setParameter("o", o.id()).getResultList();
        for (PaymentRecord p : o.payments()) {
            if (!existing.contains(p.id())) {
                em.persist(new PaymentRecordJpaEntity(p.id(), o.id(), p.amount().amount(), p.method().name(),
                        p.reference(), p.recordedBy(), p.recordedAt()));
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SalesOrder> findByIdAndCustomer(UUID tenantId, UUID id, UUID customerUserId) {
        return em.createQuery("select o from SalesOrderJpaEntity o where o.tenantId = :t and o.id = :id "
                                + "and o.customerUserId = :c and o.channel = 'ONLINE'", SalesOrderJpaEntity.class)
                .setParameter("t", tenantId).setParameter("id", id).setParameter("c", customerUserId)
                .getResultList().stream().findFirst().map(e -> assemble(List.of(e)).get(0));
    }

    @Override
    @Transactional
    public void lockLines(UUID tenantId, UUID orderId) {
        em.createQuery("select l from SalesOrderLineJpaEntity l where l.tenantId = :t and l.orderId = :o "
                        + "order by l.lineNo", SalesOrderLineJpaEntity.class)
                .setParameter("t", tenantId).setParameter("o", orderId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SalesOrder> findById(UUID tenantId, UUID id) {
        return em.createQuery("select o from SalesOrderJpaEntity o where o.tenantId = :t and o.id = :id",
                        SalesOrderJpaEntity.class).setParameter("t", tenantId).setParameter("id", id)
                .getResultList().stream().findFirst().map(e -> assemble(List.of(e)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SalesOrder> findByClientRequestId(UUID tenantId, UUID clientRequestId) {
        return em.createQuery("select o from SalesOrderJpaEntity o where o.tenantId = :t "
                                + "and o.clientRequestId = :c", SalesOrderJpaEntity.class)
                .setParameter("t", tenantId).setParameter("c", clientRequestId)
                .getResultList().stream().findFirst().map(e -> assemble(List.of(e)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findExpiredReservationIds(UUID tenantId, Instant now, int limit) {
        return em.createQuery("select o.id from SalesOrderJpaEntity o where o.tenantId = :t "
                                + "and o.status = 'PENDING_CONFIRMATION' and o.reservationExpiresAt is not null "
                                + "and o.reservationExpiresAt <= :now order by o.reservationExpiresAt, o.id",
                        UUID.class)
                .setParameter("t", tenantId).setParameter("now", now).setMaxResults(limit).getResultList();
    }

    @Override
    @Transactional
    public Optional<SalesOrder> findByIdSkipLocked(UUID tenantId, UUID id) {
        return em.createQuery("select o from SalesOrderJpaEntity o where o.tenantId = :t and o.id = :id",
                        SalesOrderJpaEntity.class).setParameter("t", tenantId).setParameter("id", id)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint("jakarta.persistence.lock.timeout", org.hibernate.LockOptions.SKIP_LOCKED)
                .getResultList().stream().findFirst().map(e -> assemble(List.of(e)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public long countPendingOnline(UUID tenantId, UUID customerUserId) {
        return em.createQuery("select count(o) from SalesOrderJpaEntity o where o.tenantId = :t "
                        + "and o.customerUserId = :c and o.channel = 'ONLINE' "
                        + "and o.status in ('PENDING_CONFIRMATION', 'AWAITING_PAYMENT')",
                Long.class).setParameter("t", tenantId).setParameter("c", customerUserId).getSingleResult();
    }

    @Override
    @Transactional(readOnly = true)
    public long countOnlineSince(UUID tenantId, UUID customerUserId, Instant since) {
        return em.createQuery("select count(o) from SalesOrderJpaEntity o where o.tenantId = :t "
                        + "and o.customerUserId = :c and o.channel = 'ONLINE' and o.createdAt >= :since",
                Long.class).setParameter("t", tenantId).setParameter("c", customerUserId)
                .setParameter("since", since).getSingleResult();
    }

    @Override
    @Transactional(readOnly = true)
    public long countCancelledOnlineSince(UUID tenantId, UUID customerUserId, Instant since) {
        return em.createQuery("select count(o) from SalesOrderJpaEntity o where o.tenantId = :t "
                        + "and o.customerUserId = :c and o.channel = 'ONLINE' and o.status = 'CANCELLED' "
                        + "and o.createdAt >= :since",
                Long.class).setParameter("t", tenantId).setParameter("c", customerUserId)
                .setParameter("since", since).getSingleResult();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<SalesOrder> search(UUID tenantId, Criteria c, int page, int size) {
        boolean hasKeyword = c.keyword() != null && !c.keyword().isBlank();
        String where = "from SalesOrderJpaEntity o where o.tenantId = :t"
                + (c.status() != null ? " and o.status = :status" : "")
                + (c.paymentStatus() != null ? " and o.paymentStatus = :pstatus" : "")
                + (c.channel() != null ? " and o.channel = :channel" : "")
                + (c.customerUserId() != null ? " and o.customerUserId = :cust" : "")
                + (c.from() != null ? " and o.createdAt >= :from" : "")
                + (c.to() != null ? " and o.createdAt < :to" : "")
                + (hasKeyword ? " and lower(o.orderNo) like :kw escape '\\'" : "");
        var count = em.createQuery("select count(o) " + where, Long.class);
        var rows = em.createQuery("select o " + where + " order by o.createdAt desc, o.id desc",
                SalesOrderJpaEntity.class);
        for (var q : List.of(count, rows)) {
            q.setParameter("t", tenantId);
            if (c.status() != null) q.setParameter("status", c.status().name());
            if (c.paymentStatus() != null) q.setParameter("pstatus", c.paymentStatus().name());
            if (c.channel() != null) q.setParameter("channel", c.channel().name());
            if (c.customerUserId() != null) q.setParameter("cust", c.customerUserId());
            if (c.from() != null) q.setParameter("from", c.from());
            if (c.to() != null) q.setParameter("to", c.to());
            if (hasKeyword) {
                q.setParameter("kw", "%" + Like.escape(c.keyword().trim().toLowerCase(Locale.ROOT)) + "%");
            }
        }
        List<SalesOrderJpaEntity> entities = rows.setFirstResult(page * size).setMaxResults(size).getResultList();
        return new PageResult<>(assemble(entities), count.getSingleResult(), page, size);
    }

    private Optional<SalesOrder> load(UUID id) {
        return em.createQuery("select o from SalesOrderJpaEntity o where o.id = :id", SalesOrderJpaEntity.class)
                .setParameter("id", id).getResultList().stream().findFirst()
                .map(e -> assemble(List.of(e)).get(0));
    }

    /** Dựng aggregate cho một lô đơn: dòng và thanh toán nạp bằng hai truy vấn IN cho cả lô. */
    private List<SalesOrder> assemble(List<SalesOrderJpaEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = entities.stream().map(SalesOrderJpaEntity::getId).toList();
        Map<UUID, List<OrderLine>> lines = new HashMap<>();
        em.createQuery("select l from SalesOrderLineJpaEntity l where l.orderId in :ids", SalesOrderLineJpaEntity.class)
                .setParameter("ids", ids).getResultList().stream()
                .sorted(Comparator.comparingInt(SalesOrderLineJpaEntity::getLineNo))
                .forEach(l -> lines.computeIfAbsent(l.getOrderId(), k -> new ArrayList<>()).add(new OrderLine(
                        l.getId(), l.getLineNo(), l.getTitleId(), l.getTitleName(), l.getIsbn(), l.getQuantity(),
                        Money.of(l.getUnitPrice()), Money.of(l.getLineDiscount()),
                        Money.of(l.getAllocatedOrderDiscount()), Money.of(l.getAmount()),
                        Money.of(l.getVatAmount()), Money.of(l.getLineTotal()))));
        Map<UUID, List<PaymentRecord>> payments = new HashMap<>();
        em.createQuery("select p from PaymentRecordJpaEntity p where p.orderId in :ids order by p.recordedAt",
                        PaymentRecordJpaEntity.class)
                .setParameter("ids", ids).getResultList()
                .forEach(p -> payments.computeIfAbsent(p.getOrderId(), k -> new ArrayList<>()).add(new PaymentRecord(
                        p.getId(), Money.of(p.getAmount()), PaymentMethod.valueOf(p.getMethod()), p.getReference(),
                        p.getRecordedBy(), p.getRecordedAt())));
        return entities.stream().map(e -> toDomain(e, lines.getOrDefault(e.getId(), List.of()),
                payments.getOrDefault(e.getId(), List.of()))).toList();
    }

    private static SalesOrder toDomain(SalesOrderJpaEntity e, List<OrderLine> lines, List<PaymentRecord> payments) {
        Discount discount = e.getOrderDiscountType() == null ? null
                : new Discount(Discount.Type.valueOf(e.getOrderDiscountType()), e.getOrderDiscountValue());
        Recipient recipient = e.getRecipientName() == null && e.getRecipientPhone() == null
                && e.getRecipientAddress() == null ? null
                : new Recipient(e.getRecipientName(), e.getRecipientPhone(), e.getRecipientAddress());
        return SalesOrder.restore(new SalesOrder.Snapshot(e.getId(), e.getTenantId(), e.getOrderNo(),
                OrderChannel.valueOf(e.getChannel()), OrderStatus.valueOf(e.getStatus()),
                PaymentStatus.valueOf(e.getPaymentStatus()), PaymentMethod.valueOf(e.getPaymentMethod()),
                e.getCustomerUserId(), e.getWalkInName(), e.getWalkInPhone(), Fulfilment.valueOf(e.getFulfilment()),
                recipient, e.getVatRateBps(), e.isPricesIncludeVat(), Money.of(e.getSubtotal()),
                Money.of(e.getDiscountTotal()), Money.of(e.getVatTotal()), Money.of(e.getShippingFee()),
                Money.of(e.getGrandTotal()), discount, e.getClientRequestId(), e.getExchangeOfReturnId(),
                e.getCreatedBy(), e.getCreatedAt(), e.getConfirmedAt(), e.getConfirmedBy(), e.getPaidAt(),
                e.getCompletedAt(), e.getCancelledAt(), e.getCancelledBy(), e.getCancelReason(),
                e.getPiiAnonymizedAt(), lines, payments, e.getVersion(), e.getCustomerNote(), e.getStaffNote(),
                e.getExpectedDeliveryDate(), e.getReservationExpiresAt(), e.getRequestHash()));
    }
}
