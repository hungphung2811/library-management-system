package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;

/** Vị trí kệ (bảng shelf_locations): "tên" của danh mục là cột code, khóa so trùng là code_key. */
@Entity
@Table(name = "shelf_locations")
@AttributeOverrides({
        @AttributeOverride(name = "name", column = @Column(name = "code", nullable = false, length = 64)),
        @AttributeOverride(name = "nameKey", column = @Column(name = "code_key", nullable = false, length = 64))
})
class ShelfLocationJpaEntity extends ReferenceJpaEntity {

    @Column(length = 20)
    private String floor;

    @Column(length = 50)
    private String zone;

    private Integer capacity;

    protected ShelfLocationJpaEntity() {
    }

    ShelfLocationJpaEntity(Reference r) {
        super(r);
        ReferenceExtras x = r.extras();
        this.floor = x.floor();
        this.zone = x.zone();
        this.capacity = x.capacity();
    }

    @Override
    ReferenceExtras extras() {
        return ReferenceExtras.shelf(floor, zone, capacity);
    }

    @Override
    ReferenceKind kind() { return ReferenceKind.SHELF_LOCATION; }
}
