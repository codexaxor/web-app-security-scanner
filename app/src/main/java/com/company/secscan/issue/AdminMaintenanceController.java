package com.company.secscan.issue;

import com.company.secscan.auth.CurrentWorkspace;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-triggerable one-off maintenance (api profile; {@code /api/v1/admin/**} is operator-only, D-094). Currently
 * the OD-19 ignored-orphan retire pass (D-083). It acts ONLY on the caller's own workspace ({@code @CurrentWorkspace}
 * — the operator's Default workspace): it is NOT a cross-workspace sweep, so it never reveals or changes another
 * tenant's findings (D-094).
 */
@RestController
@Profile("api")
@RequestMapping("/api/v1/admin/maintenance")
public class AdminMaintenanceController {

    private final IgnoredOrphanRetirer retirer;

    public AdminMaintenanceController(IgnoredOrphanRetirer retirer) {
        this.retirer = retirer;
    }

    /**
     * Retire stale ignored orphans (OD-19). {@code dryRun} defaults to {@code true}: it returns the candidate
     * list (sub-issue ids, rule, file, reason) and changes nothing. {@code dryRun=false} solves each candidate
     * and writes a {@code triage_event} (ignored → solved, SOLVED_NOT_DETECTED). Idempotent.
     */
    @PostMapping("/retire-ignored-orphans")
    public IgnoredOrphanRetirer.RetireResult retireIgnoredOrphans(@CurrentWorkspace UUID workspaceId,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return retirer.retire(workspaceId, dryRun);
    }
}
