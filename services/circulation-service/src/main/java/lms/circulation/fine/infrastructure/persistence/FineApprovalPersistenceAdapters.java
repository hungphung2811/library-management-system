package lms.circulation.fine.infrastructure.persistence;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.circulation.fine.domain.ApprovalStatus;
import lms.circulation.fine.domain.FineAdjustmentRepository;
import lms.circulation.fine.domain.FineAdjustmentRequest;
import lms.circulation.fine.domain.FineRefund;
import lms.circulation.fine.domain.FineRefundRepository;
import lms.circulation.fine.domain.FineRuleException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/**
 * Adapter lưu đề nghị miễn/giảm và đề nghị hoàn. Danh sách phân trang ở DB bằng một truy vấn lọc (Specification) rồi
 * một truy vấn đếm, không nạp quan hệ nên không có N+1; tenant do Hibernate/RLS lọc.
 */
public final class FineApprovalPersistenceAdapters {

    private FineApprovalPersistenceAdapters() {
    }

    @Repository
    static class AdjustmentAdapter implements FineAdjustmentRepository {

        private final SpringDataFineAdjustmentRepository jpa;

        AdjustmentAdapter(SpringDataFineAdjustmentRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        public FineAdjustmentRequest save(FineAdjustmentRequest request) {
            try {
                jpa.saveAndFlush(new FineAdjustmentJpaEntity(request));
            } catch (DataIntegrityViolationException e) {
                // uq_fine_adjustment_pending: hai thủ thư đề nghị cùng một khoản đồng thời
                throw FineRuleException.conflict("ADJUSTMENT_ALREADY_PENDING",
                        "Khoản phạt đã có đề nghị miễn/giảm đang chờ duyệt");
            }
            return request;
        }

        @Override
        public Optional<FineAdjustmentRequest> findByIdForUpdate(UUID id) {
            return jpa.findByIdForUpdate(id).map(FineAdjustmentJpaEntity::toDomain);
        }

        @Override
        public boolean existsPendingForFine(UUID fineId) {
            return jpa.existsByFineIdAndStatus(fineId, ApprovalStatus.PENDING);
        }

        @Override
        public Page search(Criteria c) {
            Specification<FineAdjustmentJpaEntity> spec = (root, query, cb) -> {
                List<Predicate> p = new ArrayList<>();
                if (c.status() != null) p.add(cb.equal(root.get("status"), c.status()));
                if (c.fineId() != null) p.add(cb.equal(root.get("fineId"), c.fineId()));
                if (c.readerId() != null) p.add(cb.equal(root.get("readerId"), c.readerId()));
                return cb.and(p.toArray(new Predicate[0]));
            };
            var page = jpa.findAll(spec, PageRequest.of(c.page(), c.size(),
                    Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.asc("id"))));
            return new Page(page.getContent().stream().map(FineAdjustmentJpaEntity::toDomain).toList(),
                    page.getTotalElements());
        }
    }

    @Repository
    static class RefundAdapter implements FineRefundRepository {

        private static final Set<ApprovalStatus> ACTIVE = Set.of(ApprovalStatus.PENDING, ApprovalStatus.APPROVED);

        private final SpringDataFineRefundRepository jpa;

        RefundAdapter(SpringDataFineRefundRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        public FineRefund save(FineRefund refund) {
            try {
                jpa.saveAndFlush(new FineRefundJpaEntity(refund));
            } catch (DataIntegrityViolationException e) {
                // uq_fine_refunds_active: mỗi khoản mất chỉ hoàn một lần
                throw FineRuleException.conflict("REFUND_ALREADY_EXISTS",
                        "Khoản bồi thường mất này đã có đề nghị hoàn (chờ duyệt hoặc đã duyệt)");
            }
            return refund;
        }

        @Override
        public Optional<FineRefund> findByIdForUpdate(UUID id) {
            return jpa.findByIdForUpdate(id).map(FineRefundJpaEntity::toDomain);
        }

        @Override
        public boolean existsActiveForFine(UUID fineId) {
            return jpa.existsByFineIdAndStatusIn(fineId, ACTIVE);
        }

        @Override
        public Page search(Criteria c) {
            Specification<FineRefundJpaEntity> spec = (root, query, cb) -> {
                List<Predicate> p = new ArrayList<>();
                if (c.status() != null) p.add(cb.equal(root.get("status"), c.status()));
                if (c.readerId() != null) p.add(cb.equal(root.get("readerId"), c.readerId()));
                return cb.and(p.toArray(new Predicate[0]));
            };
            var page = jpa.findAll(spec, PageRequest.of(c.page(), c.size(),
                    Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.asc("id"))));
            return new Page(page.getContent().stream().map(FineRefundJpaEntity::toDomain).toList(),
                    page.getTotalElements());
        }
    }
}
