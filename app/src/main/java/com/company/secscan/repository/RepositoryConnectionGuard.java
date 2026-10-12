package com.company.secscan.repository;

import com.company.secscan.workspace.Workspace;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the provisional state of repositories that were reassigned when their previous connection was removed
 * (D-020). Each method commits in its own transaction ({@code REQUIRES_NEW}) so a deactivation survives even when
 * the surrounding use (e.g. a project listing) then fails and rolls back.
 */
@Component
public class RepositoryConnectionGuard {

    private static final Logger log = LoggerFactory.getLogger(RepositoryConnectionGuard.class);
    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    static final String NO_ACCESSIBLE_CONNECTION = "NO_ACCESSIBLE_CONNECTION";

    private final RepositoryRepository repositories;

    public RepositoryConnectionGuard(RepositoryRepository repositories) {
        this.repositories = repositories;
    }

    /** The connection worked: a provisional reassignment onto it is now confirmed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void confirm(UUID connectionId) {
        for (Repository repo : repositories.findByWorkspaceIdAndConnectionId(WORKSPACE, connectionId)) {
            if (repo.isProvisional()) {
                repo.confirmConnection();
                repositories.save(repo);
            }
        }
    }

    /** The connection cannot reach its repositories: deactivate the provisionally reassigned ones. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deactivate(UUID connectionId) {
        for (Repository repo : repositories.findByWorkspaceIdAndConnectionId(WORKSPACE, connectionId)) {
            if (repo.isProvisional()) {
                repo.deactivate(NO_ACCESSIBLE_CONNECTION);
                repositories.save(repo);
                log.info("Deactivated repository {}: reassignment to connection {} has no access ({})",
                        repo.getId(), connectionId, NO_ACCESSIBLE_CONNECTION);
            }
        }
    }
}
