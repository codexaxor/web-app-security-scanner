package com.company.secscan.signup;

import java.time.Instant;
import java.util.UUID;

/** What the operator sees of a sign-up request (D-094: metadata only — never the password hash). */
public record SignupRequestView(UUID id, String email, String workspaceName, String status, Instant createdAt,
                                Instant decidedAt, UUID workspaceId) {

    static SignupRequestView of(SignupRequest r) {
        return new SignupRequestView(r.getId(), r.getEmail(), r.getRequestedWorkspaceName(), r.getStatus(),
                r.getCreatedAt(), r.getDecidedAt(), r.getWorkspaceId());
    }
}
