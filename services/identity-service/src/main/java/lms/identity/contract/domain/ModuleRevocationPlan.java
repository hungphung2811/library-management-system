package lms.identity.contract.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Kế hoạch thu hồi module khi hợp đồng hết hạn/bị hủy (ADR-0012 mục 2.3.3), thuần tính toán:
 * <ol>
 *   <li>{@code toDisable} = module của hợp đồng đang thu hồi − module được hợp đồng khác bao phủ − module đang tắt
 *       (module bật tay ngoài mọi hợp đồng không nằm trong tập này nên không bị đụng);</li>
 *   <li>nếu tắt hết sẽ không còn module nào: giữ lại MỘT module (đầu tiên theo thứ tự enum) và suspend tenant
 *       (bất biến "còn ít nhất một module" của ADR-0009 không bao giờ bị phá, không đi đường 409).</li>
 * </ol>
 */
public record ModuleRevocationPlan(List<ContractModule> toDisable, ContractModule retained) {

    /** Tenant sẽ không còn module nào bật ngoài module giữ lại → phải suspend. */
    public boolean suspend() {
        return retained != null;
    }

    public static ModuleRevocationPlan plan(Collection<ContractModule> revoking, Collection<ContractModule> covered,
                                            Collection<ContractModule> enabled) {
        Set<ContractModule> cover = Set.copyOf(covered);
        Set<ContractModule> on = Set.copyOf(enabled);
        List<ContractModule> disable = new ArrayList<>(revoking.stream().distinct().sorted()
                .filter(m -> !cover.contains(m)).filter(on::contains).toList());
        boolean anyLeft = on.stream().anyMatch(m -> !disable.contains(m));
        ContractModule retained = null;
        if (!anyLeft && !disable.isEmpty()) {
            retained = disable.remove(0);
        }
        return new ModuleRevocationPlan(List.copyOf(disable), retained);
    }
}
