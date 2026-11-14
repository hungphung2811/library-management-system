package lms.catalog.inventory.infrastructure.persistence;

import lms.catalog.inventory.domain.CopyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "book_copies")
class BookCopyJpaEntity {

    @Id
    private UUID id;

    @Column(name = "title_id", nullable = false)
    private UUID titleId;

    @Column(nullable = false, unique = true)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CopyStatus status;

    protected BookCopyJpaEntity() {
    }

    BookCopyJpaEntity(UUID id, UUID titleId, String barcode, CopyStatus status) {
        this.id = id;
        this.titleId = titleId;
        this.barcode = barcode;
        this.status = status;
    }

    UUID getId() { return id; }
    UUID getTitleId() { return titleId; }
    String getBarcode() { return barcode; }
    CopyStatus getStatus() { return status; }
}
