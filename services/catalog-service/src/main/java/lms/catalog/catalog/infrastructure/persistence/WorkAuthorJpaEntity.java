package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Bảng nối tác phẩm - tác giả chuẩn (có thứ tự). Thuộc tenant (@TenantId + RLS). */
@Entity
@Table(name = "work_authors")
@IdClass(WorkAuthorJpaEntity.Key.class)
class WorkAuthorJpaEntity {

    static class Key implements Serializable {
        private UUID workId;
        private UUID authorId;

        Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(workId, k.workId) && Objects.equals(authorId, k.authorId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workId, authorId);
        }
    }

    @Id
    @Column(name = "work_id", nullable = false)
    private UUID workId;

    @Id
    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected WorkAuthorJpaEntity() {
    }

    WorkAuthorJpaEntity(UUID workId, UUID authorId, int position) {
        this.tenantId = TenantContext.require();
        this.workId = workId;
        this.authorId = authorId;
        this.sortOrder = position;
    }

    UUID getWorkId() { return workId; }
    UUID getAuthorId() { return authorId; }
}
