package lms.identity.contract.infrastructure.web;

import java.util.List;
import lms.identity.contract.application.ContractResponses.TenantContractView;
import lms.identity.contract.application.GetMyContractsUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant admin xem hợp đồng của mình (chỉ đọc, ADR-0012 mục 2.5.2). KHÔNG có annotation quyền: khóa động
 * {@code TenantContractController/list} và {@code TenantContractController/current} do PermissionInterceptor tự
 * đăng ký và kiểm (role is_admin đi qua sẵn; tenant có thể gán khóa cho role khác). Đổi tên lớp/method làm đổi khóa
 * (role đã gán mất quyền). Không nhận id nào: tenant lấy từ token; lms-api định tuyến {@code /api/contracts...}.
 */
@RestController
@RequestMapping("/internal/contracts")
public class TenantContractController {

    private final GetMyContractsUseCase contracts;

    public TenantContractController(GetMyContractsUseCase contracts) {
        this.contracts = contracts;
    }

    @GetMapping
    public List<TenantContractView> list() {
        return contracts.list();
    }

    @GetMapping("/current")
    public TenantContractView current() {
        return contracts.current();
    }
}
