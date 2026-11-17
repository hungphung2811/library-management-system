package lms.system.menu.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate: toàn bộ mục menu của một tenant. Giữ bất biến cây (không chu trình, sâu tối đa
 * {@value #MAX_DEPTH} cấp, code duy nhất, tối đa {@value #MAX_ITEMS} mục) và quy tắc hiển thị.
 * Không an toàn đa luồng; mỗi use case tạo một bản.
 */
public final class MenuTree {

    public static final int MAX_DEPTH = 3;
    public static final int MAX_ITEMS = 200;

    private static final Comparator<MenuItem> ORDER = Comparator
            .comparingInt(MenuItem::sortOrder)
            .thenComparing(MenuItem::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(MenuItem::code);

    private final UUID tenantId;
    private final Map<UUID, MenuItem> items = new LinkedHashMap<>();

    public MenuTree(UUID tenantId, Collection<MenuItem> existing) {
        this.tenantId = tenantId;
        for (MenuItem item : existing) {
            items.put(item.id(), item);
        }
    }

    public int size() {
        return items.size();
    }

    public MenuItem find(UUID id) {
        MenuItem item = items.get(id);
        if (item == null) {
            throw new MenuException(MenuErrorCode.MENU_NOT_FOUND, "Không tìm thấy menu " + id);
        }
        return item;
    }

    /** Thêm mục mới (version 0). Trả mục đã tạo để tầng lưu trữ ghi. */
    public MenuItem add(UUID id, String code, MenuDetails details, Instant now) {
        MenuRules.validateCode(code);
        validateDetails(details);
        if (items.size() >= MAX_ITEMS) {
            throw new MenuException(MenuErrorCode.MENU_LIMIT_EXCEEDED,
                    "Mỗi tenant tối đa " + MAX_ITEMS + " mục menu");
        }
        for (MenuItem other : items.values()) {
            if (other.code().equals(code)) {
                throw new MenuException(MenuErrorCode.MENU_CODE_DUPLICATED, "Mã menu đã tồn tại: " + code);
            }
        }
        if (details.parentId() != null) {
            int parentDepth = depthOf(parentOrThrow(details.parentId()).id());
            if (parentDepth + 1 > MAX_DEPTH) {
                throw tooDeep();
            }
        }
        MenuItem item = new MenuItem(id, tenantId, details.parentId(), code, details.label(), details.path(),
                details.icon(), details.sortOrder(), details.enabled(), details.permissionKey(), 0L, now, now,
                details.module());
        items.put(id, item);
        return item;
    }

    /**
     * Sửa mục (kể cả đổi cha = di chuyển). {@code code} không đổi. {@code expectedVersion} phải khớp
     * version hiện tại, nếu không {@code MENU_VERSION_CONFLICT}. Version do tầng lưu trữ tăng.
     */
    public MenuItem update(UUID id, MenuDetails details, long expectedVersion, Instant now) {
        MenuItem current = find(id);
        if (current.version() != expectedVersion) {
            throw new MenuException(MenuErrorCode.MENU_VERSION_CONFLICT,
                    "Menu đã bị người khác sửa, hãy tải lại");
        }
        validateDetails(details);
        UUID newParent = details.parentId();
        if (newParent != null && !newParent.equals(current.parentId())) {
            if (newParent.equals(id) || isDescendantOrSelf(newParent, id)) {
                throw new MenuException(MenuErrorCode.MENU_CYCLE,
                        "Không thể chuyển menu vào chính nó hoặc con cháu của nó");
            }
            int parentDepth = depthOf(parentOrThrow(newParent).id());
            if (parentDepth + heightOf(id) > MAX_DEPTH) {
                throw tooDeep();
            }
        }
        MenuItem updated = new MenuItem(id, current.tenantId(), newParent, current.code(), details.label(),
                details.path(), details.icon(), details.sortOrder(), details.enabled(), details.permissionKey(),
                current.version(), current.createdAt(), now, details.module());
        items.put(id, updated);
        return updated;
    }

    /** Xóa mục lá; còn con thì từ chối ({@code MENU_HAS_CHILDREN}), không xóa dây chuyền. */
    public MenuItem remove(UUID id) {
        MenuItem item = find(id);
        for (MenuItem other : items.values()) {
            if (id.equals(other.parentId())) {
                throw new MenuException(MenuErrorCode.MENU_HAS_CHILDREN,
                        "Menu còn mục con, hãy xóa hoặc chuyển con trước");
            }
        }
        items.remove(id);
        return item;
    }

    /** Mọi mục (kể cả đang tắt) theo thứ tự duyệt cây: cha trước con, anh em theo sort_order rồi label. */
    public List<MenuItem> ordered() {
        List<MenuItem> out = new ArrayList<>();
        collect(childrenByParent(), null, out);
        return out;
    }

    /**
     * Cây hiển thị cho một người xem: mục tắt ẩn cả nhánh; mục có module không thuộc {@code viewer.modules()} ẩn cả
     * nhánh (kể cả với admin; mục lõi {@code module == null} luôn qua); mục có khóa chỉ hiện khi admin hoặc có
     * khóa; nút nhóm chỉ hiện khi còn ít nhất một con hiện.
     */
    public List<MenuNode> visibleFor(MenuViewer viewer) {
        return visibleChildren(childrenByParent(), null, viewer);
    }

    private List<MenuNode> visibleChildren(Map<UUID, List<MenuItem>> byParent, UUID parentId, MenuViewer viewer) {
        List<MenuNode> out = new ArrayList<>();
        for (MenuItem item : byParent.getOrDefault(parentId, List.of())) {
            if (!item.enabled() || !viewer.hasModule(item.module()) || !viewer.canSee(item.permissionKey())) {
                continue;
            }
            List<MenuNode> children = visibleChildren(byParent, item.id(), viewer);
            if (item.isGroup() && children.isEmpty()) {
                continue;
            }
            out.add(new MenuNode(item, children));
        }
        return out;
    }

    private void collect(Map<UUID, List<MenuItem>> byParent, UUID parentId, List<MenuItem> out) {
        for (MenuItem item : byParent.getOrDefault(parentId, List.of())) {
            out.add(item);
            collect(byParent, item.id(), out);
        }
    }

    private Map<UUID, List<MenuItem>> childrenByParent() {
        Map<UUID, List<MenuItem>> byParent = new LinkedHashMap<>();
        for (MenuItem item : items.values()) {
            byParent.computeIfAbsent(item.parentId(), k -> new ArrayList<>()).add(item);
        }
        byParent.values().forEach(list -> list.sort(ORDER));
        return byParent;
    }

    private static void validateDetails(MenuDetails d) {
        MenuRules.validateLabel(d.label());
        MenuRules.validatePath(d.path());
        MenuRules.validateIcon(d.icon());
        MenuRules.validatePermissionKey(d.permissionKey());
        MenuRules.validateModule(d.module());
    }

    private MenuItem parentOrThrow(UUID parentId) {
        MenuItem parent = items.get(parentId);
        if (parent == null) {
            throw new MenuException(MenuErrorCode.MENU_PARENT_NOT_FOUND, "Không tìm thấy menu cha " + parentId);
        }
        return parent;
    }

    /** Độ sâu của mục (gốc = 1). Dữ liệu đã có chu trình thì báo MENU_CYCLE thay vì lặp vô hạn. */
    private int depthOf(UUID id) {
        int depth = 0;
        Set<UUID> seen = new HashSet<>();
        UUID cursor = id;
        while (cursor != null) {
            if (!seen.add(cursor)) {
                throw new MenuException(MenuErrorCode.MENU_CYCLE, "Dữ liệu menu có chu trình");
            }
            depth++;
            MenuItem item = items.get(cursor);
            cursor = item == null ? null : item.parentId();
        }
        return depth;
    }

    /** Chiều cao của cây con gốc tại id (lá = 1). */
    private int heightOf(UUID id) {
        int max = 0;
        for (MenuItem other : items.values()) {
            if (id.equals(other.parentId())) {
                max = Math.max(max, heightOf(other.id()));
            }
        }
        return max + 1;
    }

    /** {@code candidate} có là {@code ancestorId} hoặc con cháu của nó không (đi ngược từ candidate lên). */
    private boolean isDescendantOrSelf(UUID candidate, UUID ancestorId) {
        Set<UUID> seen = new HashSet<>();
        UUID cursor = candidate;
        while (cursor != null && seen.add(cursor)) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            MenuItem item = items.get(cursor);
            cursor = item == null ? null : item.parentId();
        }
        return false;
    }

    private static MenuException tooDeep() {
        return new MenuException(MenuErrorCode.MENU_TOO_DEEP, "Menu sâu tối đa " + MAX_DEPTH + " cấp");
    }
}
