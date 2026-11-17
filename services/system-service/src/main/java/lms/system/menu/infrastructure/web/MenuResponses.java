package lms.system.menu.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.domain.MenuNode;

/** Response của API menu. Không lộ permissionKey/enabled cho người dùng thường (chỉ có ở {@link AdminNode}). */
public final class MenuResponses {

    private MenuResponses() {
    }

    /** Nút của cây menu đã lọc cho người đang đăng nhập. */
    public record Node(UUID id, String code, String label, String path, String icon, List<Node> children) {

        static Node from(MenuNode n) {
            MenuItem i = n.item();
            return new Node(i.id(), i.code(), i.label(), i.path(), i.icon(),
                    n.children().stream().map(Node::from).toList());
        }
    }

    /** Một mục đầy đủ cho màn quản trị (danh phẳng, cha trước con; dựng cây theo {@code parentId}). */
    public record AdminNode(UUID id, String code, String label, String path, String icon, UUID parentId,
                            int sortOrder, boolean enabled, String permissionKey, long version, Instant updatedAt,
                            String module) {

        static AdminNode from(MenuItem i) {
            return new AdminNode(i.id(), i.code(), i.label(), i.path(), i.icon(), i.parentId(), i.sortOrder(),
                    i.enabled(), i.permissionKey(), i.version(), i.updatedAt(), i.module());
        }
    }

    /** Danh sách quản trị kèm cờ: usingDefaults = true khi tenant chưa có dữ liệu (items rỗng, /me dùng bộ mặc định). */
    public record AdminList(boolean usingDefaults, List<AdminNode> items) {}
}
