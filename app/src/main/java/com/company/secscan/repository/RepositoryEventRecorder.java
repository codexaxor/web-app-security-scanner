package com.company.secscan.repository;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Persists repository lifecycle events (D-028) at the D-020 sites that previously only logged. Each method saves
 * within the caller's transaction, so the event commits with the state change it records.
 */
@Component
public class RepositoryEventRecorder {

    private final RepositoryEventRepository events;

    public RepositoryEventRecorder(RepositoryEventRepository events) {
        this.events = events;
    }

    /** A repository provisionally reassigned from one connection to another on removal. */
    public void reassigned(Repository repo, UUID fromConnectionId, UUID toConnectionId) {
        save(repo, RepositoryEvent.REASSIGNED, fromConnectionId, toConnectionId, null, null);
    }

    /** A repository detached (no connection) and deactivated because no connection could reach it on removal. */
    public void detached(Repository repo, UUID fromConnectionId, String reasonCode) {
        save(repo, RepositoryEvent.DETACHED, fromConnectionId, null, reasonCode, null);
    }

    /** A provisional reassignment target was found to have no access: the repository was deactivated. */
    public void deactivated(Repository repo, UUID connectionId, String reasonCode) {
        save(repo, RepositoryEvent.DEACTIVATED, connectionId, null, reasonCode, null);
    }

    /** A provisional reassignment was confirmed: the connection reached the repository. */
    public void confirmed(Repository repo, UUID connectionId) {
        save(repo, RepositoryEvent.CONFIRMED, null, connectionId, null, null);
    }

    private void save(Repository repo, String type, UUID from, UUID to, String reason, String detail) {
        events.save(new RepositoryEvent(repo.getWorkspaceId(), repo.getId(), type, from, to, reason, detail));
    }
}
