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

/** Bảng nối đầu sách - tác giả chuẩn (nhiều tác giả, có thứ tự). Thuộc tenant (@TenantId + RLS). */
@Entity
@Table(name = "title_authors")
@IdClass(TitleAuthorJpaEntity.Key.class)
class TitleAuthorJpaEntity {

    static class Key implements Serializable {
        private UUID titleId;
        private UUID authorId;

        Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(titleId, k.titleId) && Objects.equals(authorId, k.authorId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(titleId, authorId);
        }
    }

    @Id
    @Column(name = "title_id", nullable = false)
    private UUID titleId;

    @Id
    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected TitleAuthorJpaEntity() {
    }

    TitleAuthorJpaEntity(UUID titleId, UUID authorId, int position) {
        this.tenantId = TenantContext.require();
        this.titleId = titleId;
        this.authorId = authorId;
        this.sortOrder = position;
    }

    UUID getTitleId() { return titleId; }
    UUID getAuthorId() { return authorId; }
    int getSortOrder() { return sortOrder; }
}
