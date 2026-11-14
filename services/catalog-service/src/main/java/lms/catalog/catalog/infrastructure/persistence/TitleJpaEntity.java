package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "titles")
class TitleJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String author;

    private String isbn;

    @Column(name = "cover_price", nullable = false)
    private BigDecimal coverPrice;

    protected TitleJpaEntity() {
    }

    TitleJpaEntity(UUID id, String name, String author, String isbn, BigDecimal coverPrice) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.isbn = isbn;
        this.coverPrice = coverPrice;
    }

    UUID getId() { return id; }
    String getName() { return name; }
    String getAuthor() { return author; }
    String getIsbn() { return isbn; }
    BigDecimal getCoverPrice() { return coverPrice; }
}
