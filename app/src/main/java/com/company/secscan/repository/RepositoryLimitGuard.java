package com.company.secscan.repository;

import com.company.secscan.api.ApiErrorException;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fair use (D-097): at most {@code secscan.fair-use.max-repositories} (25) ACTIVE repositories per workspace —
 * every workspace, Default included. Existing repositories above the limit are kept; only adding or reactivating
 * is refused, with 409 {@code REPOSITORY_LIMIT_REACHED}.
 *
 * <p>Two checks: {@link #precheck} refuses a whole selection batch up front (before any write), and
 * {@link #requireRoomFor} runs inside every transaction that turns a row active, holding the workspace row lock
 * so two concurrent requests cannot both take the last slot. DB-only — never wrapped around network I/O.
 */
@Component
public class RepositoryLimitGuard {

    public static final String CODE = "REPOSITORY_LIMIT_REACHED";

    private final RepositoryRepository repositories;
    private final WorkspaceRepository workspaces;
    private final int maxRepositories;

    public RepositoryLimitGuard(RepositoryRepository repositories, WorkspaceRepository workspaces,
                                @Value("${secscan.fair-use.max-repositories:25}") int maxRepositories) {
        this.repositories = repositories;
        this.workspaces = workspaces;
        this.maxRepositories = maxRepositories;
    }

    /** Refuses a batch that would activate {@code activating} more repositories than the workspace has room for. */
    public void precheck(UUID workspaceId, long activating) {
        check(workspaceId, activating);
    }

    /** Inside the activating transaction: locks the workspace row, then counts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRoomFor(UUID workspaceId, long activating) {
        workspaces.lockById(workspaceId);
        check(workspaceId, activating);
    }

    public int maxRepositories() {
        return maxRepositories;
    }

    private void check(UUID workspaceId, long activating) {
        if (activating <= 0) {
            return;
        }
        long active = repositories.countByWorkspaceIdAndActive(workspaceId, true);
        if (active + activating > maxRepositories) {
            throw new ApiErrorException(HttpStatus.CONFLICT, CODE, "This workspace can have at most "
                    + maxRepositories + " active repositories (" + active + " active now). Deactivate one to add "
                    + "another.");
        }
    }
}
