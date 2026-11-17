package lms.sales.stock.domain;

import java.util.UUID;
import lms.sales.shared.domain.SalesException;

public final class Supplier {

    private final UUID id;
    private final UUID tenantId;
    private String name;
    private String phone;
    private String email;
    private String address;
    private String note;
    private boolean active;

    private Supplier(UUID id, UUID tenantId, String name, String phone, String email, String address, String note,
                     boolean active) {
        this.id = id;
        this.tenantId = tenantId;
        this.active = active;
        apply(name, phone, email, address, note);
    }

    public static Supplier create(UUID tenantId, String name, String phone, String email, String address,
                                  String note) {
        return new Supplier(UUID.randomUUID(), tenantId, name, phone, email, address, note, true);
    }

    public static Supplier restore(UUID id, UUID tenantId, String name, String phone, String email, String address,
                                   String note, boolean active) {
        return new Supplier(id, tenantId, name, phone, email, address, note, active);
    }

    public void update(String name, String phone, String email, String address, String note, boolean active) {
        apply(name, phone, email, address, note);
        this.active = active;
    }

    private void apply(String name, String phone, String email, String address, String note) {
        if (name == null || name.isBlank()) {
            throw SalesException.invalidRequest("Tên nhà cung cấp không được trống");
        }
        if (name.trim().length() > 200) {
            throw SalesException.invalidRequest("Tên nhà cung cấp tối đa 200 ký tự");
        }
        this.name = name.trim();
        this.phone = blankToNull(phone);
        this.email = blankToNull(email);
        this.address = blankToNull(address);
        this.note = blankToNull(note);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String name() { return name; }
    public String phone() { return phone; }
    public String email() { return email; }
    public String address() { return address; }
    public String note() { return note; }
    public boolean active() { return active; }
}
