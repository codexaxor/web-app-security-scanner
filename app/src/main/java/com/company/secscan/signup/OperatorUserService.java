package com.company.secscan.signup;

import com.company.secscan.api.ApiErrorException;
import com.company.secscan.api.PagedResponse;
import com.company.secscan.api.Pagination;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator's user management (D-094): list accounts (metadata only — email, status, workspace NAME, dates;
 * never another tenant's repositories, findings or settings), disable and re-enable. Workspace-agnostic by design
 * (a platform-operator view, CLAUDE.md rule 1). Disabling takes effect on the user's next request
 * ({@code ActiveAccountFilter}). The operator can never be disabled (DB constraint too).
 */
@Service
@Profile("api")
public class OperatorUserService {

    private static final Logger log = LoggerFactory.getLogger(OperatorUserService.class);

    /** One account as the operator sees it. */
    public record UserAdminView(UUID id, String email, String status, String workspaceName, boolean operator,
                                Instant createdAt, Instant statusChangedAt) {
    }

    private final AppUserRepository users;
    private final WorkspaceRepository workspaces;

    public OperatorUserService(AppUserRepository users, WorkspaceRepository workspaces) {
        this.users = users;
        this.workspaces = workspaces;
    }

    @Transactional(readOnly = true)
    public PagedResponse<UserAdminView> list(String status, int page, int size) {
        Page<AppUser> p;
        if (status == null || status.isBlank()) {
            p = users.findAllByOrderByCreatedAtAscIdAsc(Pagination.of(page, size));
        } else if (AppUser.STATUS_ACTIVE.equals(status) || AppUser.STATUS_DISABLED.equals(status)) {
            p = users.findByStatusOrderByCreatedAtAscIdAsc(status, Pagination.of(page, size));
        } else {
            throw new ApiErrorException(HttpStatus.BAD_REQUEST, "BAD_PARAMETER", "status must be active or disabled.");
        }
        Map<UUID, String> names = workspaces.findAllById(p.getContent().stream().map(AppUser::getWorkspaceId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(Workspace::getId, Workspace::getName, (a, b) -> a));
        return PagedResponse.fromPage(p.map(u -> view(u, names)));
    }

    @Transactional
    public UserAdminView disable(UUID userId, UUID operatorId) {
        AppUser user = require(userId);
        if (user.isOperator() || user.getId().equals(operatorId)) {
            throw new ApiErrorException(HttpStatus.CONFLICT, "CANNOT_DISABLE_OPERATOR",
                    "The platform operator cannot be disabled.");
        }
        user.disable(operatorId, Instant.now());
        users.saveAndFlush(user);
        log.info("User {} disabled by the operator", user.getId());
        return view(user, names(user));
    }

    @Transactional
    public UserAdminView enable(UUID userId, UUID operatorId) {
        AppUser user = require(userId);
        user.enable(operatorId, Instant.now());
        users.saveAndFlush(user);
        log.info("User {} re-enabled by the operator", user.getId());
        return view(user, names(user));
    }

    private AppUser require(UUID userId) {
        return users.findById(userId).orElseThrow(() ->
                new ApiErrorException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such user."));
    }

    private Map<UUID, String> names(AppUser u) {
        return workspaces.findById(u.getWorkspaceId()).stream()
                .collect(Collectors.toMap(Workspace::getId, Workspace::getName));
    }

    private static UserAdminView view(AppUser u, Map<UUID, String> names) {
        return new UserAdminView(u.getId(), u.getEmail(), u.getStatus(), names.get(u.getWorkspaceId()),
                u.isOperator(), u.getCreatedAt(), u.getStatusChangedAt());
    }
}
