package lms.api.circulation.web;

import jakarta.validation.Valid;
import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.CirculationPolicyView;
import lms.api.circulation.dto.UpdateCirculationPolicyRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API cho FE: chính sách mượn trả của đơn vị. Quyền do circulation-service kiểm. */
@RestController
@RequestMapping("/api/circulation/policy")
public class CirculationPolicyController {

    private final CirculationClient circulation;

    public CirculationPolicyController(CirculationClient circulation) {
        this.circulation = circulation;
    }

    @GetMapping
    public CirculationPolicyView get() {
        return circulation.getPolicy();
    }

    @PutMapping
    public CirculationPolicyView update(@Valid @RequestBody UpdateCirculationPolicyRequest request) {
        return circulation.updatePolicy(request);
    }
}
