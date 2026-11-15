package lms.tenant.rls;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Entity mẫu thuộc tenant (chỉ dùng trong test): Hibernate tự lọc và tự điền tenant_id. */
@Entity
@Table(name = "rls_notes")
public class RlsNote {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String content;

    protected RlsNote() {
    }

    public RlsNote(UUID id, String content) {
        this.id = id;
        this.content = content;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getContent() {
        return content;
    }
}
