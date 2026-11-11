package com.company.secscan.signup;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.auth.SecscanPrincipal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's sign-up queue (D-094). Under {@code /api/v1/admin/**}, so the security chain admits only the
 * platform operator ({@code ROLE_OPERATOR}); everyone else gets 403.
 */
@RestController
@Profile("api")
@RequestMapping("/api/v1/admin/sign-ups")
public class OperatorSignupController {

    private final SignupApprovalService service;

    public OperatorSignupController(SignupApprovalService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<SignupRequestView> list(@RequestParam(defaultValue = "pending") String status,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        return service.list(status, page, size);
    }

    @PostMapping("/{id}/approve")
    public SignupRequestView approve(@PathVariable UUID id, @AuthenticationPrincipal SecscanPrincipal operator) {
        return service.approve(id, operator.userId());
    }

    @PostMapping("/{id}/reject")
    public SignupRequestView reject(@PathVariable UUID id, @AuthenticationPrincipal SecscanPrincipal operator) {
        return service.reject(id, operator.userId());
    }
}
