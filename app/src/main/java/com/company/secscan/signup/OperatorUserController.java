package com.company.secscan.signup;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.auth.SecscanPrincipal;
import com.company.secscan.signup.OperatorUserService.UserAdminView;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The operator's user list / disable / re-enable (D-094). Operator-only via {@code /api/v1/admin/**}. */
@RestController
@Profile("api")
@RequestMapping("/api/v1/admin/users")
public class OperatorUserController {

    private final OperatorUserService service;

    public OperatorUserController(OperatorUserService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<UserAdminView> list(@RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return service.list(status, page, size);
    }

    @PostMapping("/{id}/disable")
    public UserAdminView disable(@PathVariable UUID id, @AuthenticationPrincipal SecscanPrincipal operator) {
        return service.disable(id, operator.userId());
    }

    @PostMapping("/{id}/enable")
    public UserAdminView enable(@PathVariable UUID id, @AuthenticationPrincipal SecscanPrincipal operator) {
        return service.enable(id, operator.userId());
    }
}
