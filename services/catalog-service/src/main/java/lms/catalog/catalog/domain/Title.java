package lms.catalog.catalog.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Đầu sách. Giá bìa là cơ sở tính phí bồi thường/hỏng sách (QĐ4) ở context fine. */
public class Title {

    private final UUID id;
    private final String name;
    private final String author;
    private final String isbn;
    private final BigDecimal coverPrice;

    private Title(UUID id, String name, String author, String isbn, BigDecimal coverPrice) {
        if (id == null) throw new InvalidTitleException("id không được null");
        if (name == null || name.isBlank()) throw new InvalidTitleException("Tên sách không được để trống");
        if (author == null || author.isBlank()) throw new InvalidTitleException("Tác giả không được để trống");
        if (coverPrice == null || coverPrice.signum() < 0) throw new InvalidTitleException("Giá bìa phải >= 0");
        this.id = id;
        this.name = name.trim();
        this.author = author.trim();
        this.isbn = (isbn == null || isbn.isBlank()) ? null : isbn.trim();
        this.coverPrice = coverPrice;
    }

    public static Title create(String name, String author, String isbn, BigDecimal coverPrice) {
        return new Title(UUID.randomUUID(), name, author, isbn, coverPrice);
    }

    public static Title restore(UUID id, String name, String author, String isbn, BigDecimal coverPrice) {
        return new Title(id, name, author, isbn, coverPrice);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public String author() { return author; }
    public String isbn() { return isbn; }
    public BigDecimal coverPrice() { return coverPrice; }
}
