package com.company.secscan.issue;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-triggerable one-off maintenance for the authenticated user's workspace (api profile), behind the same
 * HTTP Basic auth as the rest of {@code /api/**} (D-010, OD-9). Currently the OD-19 ignored-orphan retire pass
 * (D-083): it is NOT a cross-workspace sweep.
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
    public IgnoredOrphanRetirer.RetireResult retireIgnoredOrphans(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return retirer.retire(dryRun);
    }
}
