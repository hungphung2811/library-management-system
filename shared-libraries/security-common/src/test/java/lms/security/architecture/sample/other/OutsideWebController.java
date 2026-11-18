package lms.security.architecture.sample.other;

import lms.security.permission.IntegrationScope;
import org.springframework.web.bind.annotation.GetMapping;

/** Mẫu sai: nằm ngoài package infrastructure.web. */
public class OutsideWebController {
    @IntegrationScope("books:read")
    @GetMapping("/a")
    public String read() { return ""; }
}
