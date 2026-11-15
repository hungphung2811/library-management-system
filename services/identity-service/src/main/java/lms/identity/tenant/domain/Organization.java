package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;

/** Tổ chức nhóm nhiều tenant (ví dụ một trường có nhiều thư viện). */
public class Organization {

    private final UUID id;
    private String name;
    private final Instant createdAt;

    private Organization(UUID id, String name, Instant createdAt) {
        if (id == null) throw new InvalidTenantException("id không được null");
        requireName(name);
        this.id = id;
        this.name = name.trim();
        this.createdAt = createdAt;
    }

    public static Organization create(String name) {
        return new Organization(UUID.randomUUID(), name, Instant.now());
    }

    public static Organization restore(UUID id, String name, Instant createdAt) {
        return new Organization(id, name, createdAt);
    }

    public void rename(String newName) {
        requireName(newName);
        this.name = newName.trim();
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new InvalidTenantException("Tên tổ chức không được để trống");
        if (name.trim().length() > 255) throw new InvalidTenantException("Tên tổ chức tối đa 255 ký tự");
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public Instant createdAt() { return createdAt; }
}
