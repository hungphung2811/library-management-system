package lms.security.architecture.sample.infrastructure.web;

import lms.security.permission.TenantDataAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** Lớp mẫu cho TenantDataArchitectureRulesTest (package chứa ".infrastructure.web"). */
public final class TenantDataSamples {

    private TenantDataSamples() {
    }

    public static class Good {
        @TenantDataAccess
        @GetMapping("/internal/tenant-data/export")
        public String export() { return ""; }

        @TenantDataAccess
        @PostMapping("/internal/tenant-data/purge")
        public String purge() { return ""; }
    }

    public static class NoMapping {
        @TenantDataAccess
        public String helper() { return ""; }
    }
}
