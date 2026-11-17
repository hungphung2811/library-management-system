package lms.api.dashboard.service;

import java.util.Set;

/**
 * Module đang bật của tenant đang xem, để quyết định phần nào của summary được gọi (quyết định hiển thị, không phải
 * phân quyền: service vẫn chặn bằng @RequiresModule).
 *
 * @param enabled      null nghĩa là không xác định được (token không có claim modules): BFF gọi hết, để service quyết
 * @param lookupFailed tra module của tenant đích (platform admin) bị lỗi 5xx/mất kết nối
 * @param tenantId     tenant đích khi platform admin dùng X-Tenant-Id hợp lệ, ngược lại null
 */
public record ModuleScope(Set<String> enabled, boolean lookupFailed, String tenantId) {

    public static ModuleScope unknown() {
        return new ModuleScope(null, false, null);
    }

    public boolean known() {
        return enabled != null;
    }

    public boolean allows(String module) {
        return enabled == null || enabled.contains(module);
    }
}
