package lms.support.notification.domain;

import java.util.Set;

/** Mẫu dùng tham số ngoài danh sách cho phép của loại thông báo (400 TEMPLATE_PARAM_NOT_ALLOWED). */
public class TemplateParamNotAllowedException extends RuntimeException {

    private final Set<String> params;

    public TemplateParamNotAllowedException(Set<String> params) {
        super("Tham số không được phép trong mẫu: " + params);
        this.params = Set.copyOf(params);
    }

    public Set<String> params() {
        return params;
    }
}
