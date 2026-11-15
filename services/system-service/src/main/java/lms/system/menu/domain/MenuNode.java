package lms.system.menu.domain;

import java.util.List;

/** Nút của cây đã lọc theo quyền. */
public record MenuNode(MenuItem item, List<MenuNode> children) {

    public MenuNode {
        children = List.copyOf(children);
    }
}
