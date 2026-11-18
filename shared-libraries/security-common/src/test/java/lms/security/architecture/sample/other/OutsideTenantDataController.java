package lms.security.architecture.sample.other;

import lms.security.permission.TenantDataAccess;
import org.springframework.web.bind.annotation.GetMapping;

/** Ngoài package infrastructure.web: dùng cho TenantDataArchitectureRulesTest. */
public class OutsideTenantDataController {
    @TenantDataAccess
    @GetMapping("/x")
    public String export() { return ""; }
}
