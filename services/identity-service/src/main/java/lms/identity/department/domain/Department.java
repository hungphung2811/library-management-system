package lms.identity.department.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phòng ban trong cây tổ chức. Mã là định danh nghiệp vụ, bất biến sau khi tạo. Bất biến của cây: phòng ban
 * ACTIVE thì phòng ban cha (nếu có) cũng ACTIVE, và không có chu trình.
 */
public class Department {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{1,50}$");

    private final UUID id;
    private final String code;
    private String name;
    private UUID parentId;
    private DepartmentStatus status;

    private Department(UUID id, String code, String name, UUID parentId, DepartmentStatus status) {
        if (id == null) throw new InvalidDepartmentException("id không được null");
        if (code == null || !CODE.matcher(code.trim().toUpperCase()).matches()) {
            throw new InvalidDepartmentException("Mã phòng ban chỉ gồm chữ, số, '_' và '-', tối đa 50 ký tự");
        }
        if (name == null || name.isBlank()) throw new InvalidDepartmentException("Tên phòng ban không được để trống");
        if (name.trim().length() > 255) throw new InvalidDepartmentException("Tên phòng ban tối đa 255 ký tự");
        if (status == null) throw new InvalidDepartmentException("Trạng thái không được null");
        if (id.equals(parentId)) throw new InvalidDepartmentException("Phòng ban không thể là cha của chính nó");
        this.id = id;
        this.code = code.trim().toUpperCase();
        this.name = name.trim();
        this.parentId = parentId;
        this.status = status;
    }

    public static Department create(String code, String name, UUID parentId) {
        return new Department(UUID.randomUUID(), code, name, parentId, DepartmentStatus.ACTIVE);
    }

    public static Department restore(UUID id, String code, String name, UUID parentId, DepartmentStatus status) {
        return new Department(id, code, name, parentId, status);
    }

    public void rename(String newName) {
        if (newName == null || newName.isBlank()) throw new InvalidDepartmentException("Tên phòng ban không được để trống");
        if (newName.trim().length() > 255) throw new InvalidDepartmentException("Tên phòng ban tối đa 255 ký tự");
        this.name = newName.trim();
    }

    /** Đổi cha; việc kiểm tra cha tồn tại, ACTIVE và không tạo chu trình do use case đảm nhiệm (cần cả cây). */
    public void moveTo(UUID newParentId) {
        if (id.equals(newParentId)) throw new InvalidDepartmentException("Phòng ban không thể là cha của chính nó");
        this.parentId = newParentId;
    }

    public void deactivate() {
        this.status = DepartmentStatus.INACTIVE;
    }

    public void activate() {
        this.status = DepartmentStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == DepartmentStatus.ACTIVE;
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public UUID parentId() { return parentId; }
    public DepartmentStatus status() { return status; }
}
