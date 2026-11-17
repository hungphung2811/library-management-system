package lms.circulation.borrowing.infrastructure.persistence;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lms.circulation.borrowing.domain.BorrowingSearchCriteria;
import lms.circulation.borrowing.domain.BorrowingStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Điều kiện lọc phiếu mượn bằng Criteria API (không SQL native); @TenantId và RLS vẫn áp như mọi truy vấn JPA. */
final class BorrowingSpecifications {

    private BorrowingSpecifications() {
    }

    static Specification<BorrowingJpaEntity> matching(BorrowingSearchCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (c.readerId() != null) where.add(cb.equal(root.get("userId"), c.readerId()));
            if (c.overdueBefore() != null) where.add(cb.lessThan(root.get("dueDate"), c.overdueBefore()));
            boolean wantActive = c.status() == BorrowingStatus.ACTIVE || c.overdueBefore() != null;
            boolean wantReturned = c.status() == BorrowingStatus.RETURNED;
            if (wantActive && wantReturned) {
                where.add(cb.disjunction()); // mâu thuẫn (RETURNED mà quá hạn): không có phiếu nào
            } else if (wantActive || wantReturned) {
                // còn bản sao chưa trả: exists (select 1 from items i where i.returnDate is null), tương quan với phiếu
                Subquery<Integer> open = query.subquery(Integer.class);
                Root<BorrowingJpaEntity> outer = open.correlate(root);
                Join<BorrowingJpaEntity, BorrowingItemEmbeddable> item = outer.join("items");
                open.select(cb.literal(1)).where(cb.isNull(item.get("returnDate")));
                where.add(wantActive ? cb.exists(open) : cb.not(cb.exists(open)));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }
}
