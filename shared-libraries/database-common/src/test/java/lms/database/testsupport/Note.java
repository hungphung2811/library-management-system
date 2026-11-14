package lms.database.testsupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Entity dùng cho IT quan hệ (PostgreSQL, Oracle). */
@Entity
@Table(name = "notes")
public class Note {

    @Id
    private String id;

    @Column(name = "content", nullable = false)
    private String content;

    protected Note() {
    }

    public Note(String id, String content) {
        this.id = id;
        this.content = content;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
}
