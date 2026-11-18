package lms.support.notification.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.support.notification.application.DeliveryPort;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryStatus;
import lms.tenant.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** Adapter JPA cho DeliveryPort (AUDIT_DB). Tenant: @TenantId lọc theo TenantContext, RLS lọc thêm ở DB. */
public class DeliveryPersistenceAdapter implements DeliveryPort {

    private static final List<DeliveryStatus> DUE = List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRY);
    private static final List<DeliveryStatus> TERMINAL = List.of(DeliveryStatus.SENT, DeliveryStatus.DEAD,
            DeliveryStatus.SKIPPED);

    private final SpringDataDeliveryRepository jpa;
    private final ObjectMapper mapper;

    public DeliveryPersistenceAdapter(SpringDataDeliveryRepository jpa, ObjectMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public boolean insertIfAbsent(Delivery d) {
        return jpa.insertIfAbsent(d.id(), TenantContext.require(), d.eventId(), d.eventType(), d.type().name(),
                d.type().category().name(), d.channel().name(), d.recipientUserId(), d.recipientKey(),
                d.recipientAddress(), d.recipientAddressMasked(), json(d), d.status().name(),
                d.skipReason() == null ? null : d.skipReason().name(), d.nextAttemptAt(), d.createdAt()) == 1;
    }

    @Override
    public List<Delivery> leaseDue(int limit, Instant now) {
        List<NotificationDeliveryJpaEntity> rows = jpa.lockDue(DUE, now, PageRequest.of(0, limit));
        List<Delivery> out = new ArrayList<>(rows.size());
        for (NotificationDeliveryJpaEntity e : rows) {
            Delivery d = e.toDomain();
            d.markSending(now);
            e.apply(d);
            out.add(d);
        }
        jpa.flush();
        return out;
    }

    @Override
    public void save(Delivery d) {
        NotificationDeliveryJpaEntity e = jpa.findById(d.id()).orElseThrow();
        e.apply(d);
    }

    @Override
    public Optional<Delivery> find(UUID id) {
        return jpa.findById(id).map(NotificationDeliveryJpaEntity::toDomain);
    }

    @Override
    public long countSentSince(Instant since) {
        return jpa.countByStatusAndSentAtGreaterThanEqual(DeliveryStatus.SENT, since);
    }

    @Override
    public long countSentSinceAllTenants(Instant since) {
        return jpa.countByStatusAndSentAtGreaterThanEqual(DeliveryStatus.SENT, since);
    }

    @Override
    public int releaseStuck(Instant staleBefore, Instant now) {
        return jpa.releaseStuck(DeliveryStatus.SENDING, DeliveryStatus.RETRY, staleBefore, now);
    }

    @Override
    public DeliveryPage search(DeliveryCriteria c) {
        Specification<NotificationDeliveryJpaEntity> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (c.status() != null) p.add(cb.equal(root.get("status"), c.status()));
            if (c.channel() != null) p.add(cb.equal(root.get("channel"), c.channel()));
            if (c.type() != null && !c.type().isBlank()) {
                lms.support.notification.domain.NotificationType.parse(c.type())
                        .ifPresentOrElse(t -> p.add(cb.equal(root.get("type"), t)), () -> p.add(cb.disjunction()));
            }
            if (c.from() != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), c.from()));
            if (c.to() != null) p.add(cb.lessThan(root.<Instant>get("createdAt"), c.to()));
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<NotificationDeliveryJpaEntity> page = jpa.findAll(spec,
                PageRequest.of(c.page(), c.size(), Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new DeliveryPage(page.getContent().stream().map(NotificationDeliveryJpaEntity::toDomain).toList(),
                page.getTotalElements(), c.page(), c.size());
    }

    @Override
    public List<Delivery> findByRecipient(UUID userId, int limit) {
        return jpa.findByRecipientUserIdOrderByCreatedAtDescIdAsc(userId, PageRequest.of(0, limit)).stream()
                .map(NotificationDeliveryJpaEntity::toDomain).toList();
    }

    @Override
    public int deleteFinishedBefore(Instant before) {
        return jpa.deleteFinishedBefore(TERMINAL, before);
    }

    private String json(Delivery d) {
        try {
            return mapper.writeValueAsString(d.params());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không tuần tự hóa được tham số thông báo", e);
        }
    }
}
