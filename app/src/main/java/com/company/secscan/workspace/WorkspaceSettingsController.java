package com.company.secscan.workspace;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace settings API (M7): the EPSS auto-ignore toggle (D-060). Single-tenant, so it acts on the default
 * workspace. Changing the toggle re-evaluates LOW_EPSS across the workspace (status + scoring) in the service.
 * Per review change #8, M7 exposes only the EPSS toggle here (no editable global exclude-path list).
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1/workspace/settings")
public class WorkspaceSettingsController {

    private final WorkspaceSettingsService service;

    public WorkspaceSettingsController(WorkspaceSettingsService service) {
        this.service = service;
    }

    public record SettingsView(boolean epssAutoIgnoreEnabled) {
        static SettingsView of(WorkspaceSettings s) {
            return new SettingsView(s.isEpssAutoIgnoreEnabled());
        }
    }

    public record UpdateRequest(boolean epssAutoIgnoreEnabled) {
    }

    @GetMapping
    public SettingsView get() {
        return SettingsView.of(service.get(Workspace.DEFAULT_ID));
    }

    @PutMapping
    public SettingsView update(@RequestBody UpdateRequest req) {
        return SettingsView.of(service.setEpssAutoIgnore(Workspace.DEFAULT_ID, req.epssAutoIgnoreEnabled()));
    }
}
