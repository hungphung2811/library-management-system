package lms.circulation.borrowing.infrastructure.persistence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lms.circulation.borrowing.domain.LoanReminderKind;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Truy vấn JPQL của job nhắc hạn (Hibernate tự thêm điều kiện tenant, kể cả truy vấn con; RLS lọc thêm ở DB).
 * Chọn phiếu theo (tenant_id, due_date) bằng idx_borrowings_tenant_due_date, keyset theo id, tối đa một trang.
 */
interface SpringDataLoanReminderRepository extends JpaRepository<LoanReminderJpaEntity, UUID> {

    @Query("select b from BorrowingJpaEntity b "
            + "where b.dueDate >= :from and b.dueDate <= :to and b.id > :afterId "
            + "and exists (select 1 from b.items i where i.returnDate is null) "
            + "and not exists (select 1 from LoanReminderJpaEntity r where r.borrowingId = b.id "
            + "and r.kind = :kind and r.dueDate = b.dueDate) "
            + "order by b.id")
    List<BorrowingJpaEntity> findDueSoon(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                         @Param("afterId") UUID afterId, @Param("kind") LoanReminderKind kind,
                                         Pageable page);

    @Query("select b from BorrowingJpaEntity b "
            + "where b.dueDate < :dueBefore and b.id > :afterId "
            + "and exists (select 1 from b.items i where i.returnDate is null) "
            + "and (select count(r) from LoanReminderJpaEntity r where r.borrowingId = b.id "
            + "and r.kind = :kind and r.dueDate = b.dueDate) < :maxReminders "
            + "and not exists (select 1 from LoanReminderJpaEntity r2 where r2.borrowingId = b.id "
            + "and r2.kind = :kind and r2.dueDate = b.dueDate and r2.sentOn > :lastSentOnOrBefore) "
            + "order by b.id")
    List<BorrowingJpaEntity> findOverdue(@Param("dueBefore") LocalDate dueBefore, @Param("afterId") UUID afterId,
                                         @Param("kind") LoanReminderKind kind,
                                         @Param("maxReminders") long maxReminders,
                                         @Param("lastSentOnOrBefore") LocalDate lastSentOnOrBefore, Pageable page);

    /** Số lần nhắc đã ghi của cả lô phiếu (một truy vấn IN), nhóm theo (phiếu, hạn trả) ở bộ nhớ. */
    @Query("select r from LoanReminderJpaEntity r where r.borrowingId in :ids and r.kind = :kind")
    List<LoanReminderJpaEntity> findByBorrowingIds(@Param("ids") Collection<UUID> ids,
                                                   @Param("kind") LoanReminderKind kind);
}
