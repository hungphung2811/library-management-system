package lms.catalog.catalog.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/**
 * Một mục danh mục chuẩn (tác giả, nhà xuất bản, thể loại, vị trí kệ). Tên được chuẩn hóa (NFC, bỏ khoảng trắng
 * đầu/cuối, gộp khoảng trắng liên tiếp) và so trùng theo dạng chữ thường. Thể loại có thể có cha (tối đa 2 cấp,
 * kiểm ở use case vì cần đọc dữ liệu); các loại khác không có cha. Ngừng dùng (active=false) thay cho xóa cứng.
 */
public final class Reference {

    public static final int MAX_DESCRIPTION = 500;

    private final UUID id;
    private final ReferenceKind kind;
    private final UUID tenantId;
    private final String name;
    private final String description;
    private final UUID parentId;
    private final boolean active;

    private Reference(UUID id, ReferenceKind kind, UUID tenantId, String name, String description, UUID parentId,
                      boolean active) {
        if (kind == null) throw new IllegalArgumentException("kind không được null");
        if (id == null) throw new InvalidReferenceException(kind, "id không được null");
        String n = normalizeName(name);
        if (n.isEmpty()) {
            throw new InvalidReferenceException(kind, kind.nameLabel() + " " + kind.label() + " không được để trống");
        }
        if (n.codePointCount(0, n.length()) > kind.maxNameLength()) {
            throw new InvalidReferenceException(kind,
                    kind.nameLabel() + " " + kind.label() + " tối đa " + kind.maxNameLength() + " ký tự");
        }
        if (parentId != null && kind != ReferenceKind.CATEGORY) {
            throw new InvalidReferenceException(kind, "Chỉ thể loại mới có mục cha");
        }
        String d = description == null ? null : Normalizer.normalize(description, Normalizer.Form.NFC).strip();
        if (d != null && d.isEmpty()) d = null;
        if (d != null && d.codePointCount(0, d.length()) > MAX_DESCRIPTION) {
            throw new InvalidReferenceException(kind, "Mô tả tối đa " + MAX_DESCRIPTION + " ký tự");
        }
        this.id = id;
        this.kind = kind;
        this.tenantId = tenantId;
        this.name = n;
        this.description = d;
        this.parentId = parentId;
        this.active = active;
    }

    public static String normalizeName(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
    }

    public static Reference create(ReferenceKind kind, String name, String description, UUID parentId) {
        return new Reference(UUID.randomUUID(), kind, null, name, description, parentId, true);
    }

    public static Reference restore(UUID id, ReferenceKind kind, UUID tenantId, String name, String description,
                                    UUID parentId, boolean active) {
        return new Reference(id, kind, tenantId, name, description, parentId, active);
    }

    /** Bản đã sửa (cùng id, giữ trạng thái active). */
    public Reference update(String name, String description, UUID parentId) {
        return new Reference(id, kind, tenantId, name, description, parentId, active);
    }

    public Reference withActive(boolean active) {
        return new Reference(id, kind, tenantId, name, description, parentId, active);
    }

    /** Khóa so trùng: tên đã chuẩn hóa, chữ thường. */
    public String nameKey() { return name.toLowerCase(Locale.ROOT); }

    public UUID id() { return id; }
    public ReferenceKind kind() { return kind; }
    public UUID tenantId() { return tenantId; }
    public String name() { return name; }
    public String description() { return description; }
    public UUID parentId() { return parentId; }
    public boolean active() { return active; }
}
