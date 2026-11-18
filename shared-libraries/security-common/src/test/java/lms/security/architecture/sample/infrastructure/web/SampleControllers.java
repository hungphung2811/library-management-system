package lms.security.architecture.sample.infrastructure.web;

import lms.security.permission.IntegrationScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** Lớp mẫu cho IntegrationScopeArchitectureRulesTest (package chứa ".infrastructure.web"). */
public final class SampleControllers {

    private SampleControllers() {
    }

    public static class Good {
        @IntegrationScope("books:read")
        @GetMapping("/a")
        public String read() { return ""; }

        @IntegrationScope("books:write")
        @PostMapping("/b")
        public String write() { return ""; }
    }

    public static class UnknownScope {
        @IntegrationScope("books:delete")
        @GetMapping("/a")
        public String read() { return ""; }
    }

    public static class WriteOnGet {
        @IntegrationScope("orders:write")
        @GetMapping("/a")
        public String read() { return ""; }
    }

    public static class NoMapping {
        @IntegrationScope("books:read")
        public String helper() { return ""; }
    }

    @IntegrationScope("books:read")
    public static class OnClass {
    }
}
