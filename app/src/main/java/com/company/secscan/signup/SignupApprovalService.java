package com.company.secscan.signup;

import com.company.secscan.api.ApiErrorException;
import com.company.secscan.api.PagedResponse;
import com.company.secscan.api.Pagination;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator's sign-up decisions (D-093/D-094/D-098). <b>Approve</b> is ONE database transaction: create the
 * workspace, create the active {@code app_user} carrying the password hash copied from the request, then mark the
 * request approved (which clears the hash). <b>Reject</b> marks it rejected and clears the hash; the email may sign
 * up again. No network I/O. The request queries are workspace-agnostic by design (pre-tenant, D-098).
 */
@Service
@Profile("api")
public class SignupApprovalService {

    private static final Logger log = LoggerFactory.getLogger(SignupApprovalService.class);
    private static final Set<String> STATUSES = Set.of(SignupRequest.STATUS_PENDING, SignupRequest.STATUS_APPROVED,
            SignupRequest.STATUS_REJECTED);
    /** The person who signed up owns their workspace (D-094): full rights inside it. */
    static final String OWNER_ROLE = "admin";

    private final SignupRequestRepository requests;
    private final AppUserRepository users;
    private final WorkspaceRepository workspaces;
    private final EmailLock emailLock;

    public SignupApprovalService(SignupRequestRepository requests, AppUserRepository users,
                                 WorkspaceRepository workspaces, EmailLock emailLock) {
        this.requests = requests;
        this.users = users;
        this.workspaces = workspaces;
        this.emailLock = emailLock;
    }

    @Transactional(readOnly = true)
    public PagedResponse<SignupRequestView> list(String status, int page, int size) {
        String s = status == null || status.isBlank() ? SignupRequest.STATUS_PENDING : status.trim();
        if (!STATUSES.contains(s)) {
            throw new ApiErrorException(HttpStatus.BAD_REQUEST, "BAD_PARAMETER",
                    "status must be pending, approved or rejected.");
        }
        return PagedResponse.fromPage(requests.findByStatusOrderByCreatedAtAscIdAsc(s, Pagination.of(page, size))
                .map(SignupRequestView::of));
    }

    @Transactional
    public SignupRequestView approve(UUID requestId, UUID operatorId) {
        SignupRequest request = lockPending(requestId);
        emailLock.lock(request.getEmail());
        if (users.findByEmail(request.getEmail()).isPresent()) {
            throw new ApiErrorException(HttpStatus.CONFLICT, "EMAIL_TAKEN",
                    "An account with this email already exists.");
        }
        Workspace workspace = workspaces.saveAndFlush(new Workspace(request.getRequestedWorkspaceName()));
        // Copy the hash BEFORE approve() clears it (approval round 2, item 1) — same transaction.
        String passwordHash = request.getPasswordHash();
        users.saveAndFlush(new AppUser(workspace.getId(), request.getEmail(), passwordHash, OWNER_ROLE));
        request.approve(workspace.getId(), operatorId, Instant.now());
        requests.saveAndFlush(request);
        log.info("Sign-up {} approved: workspace {} created", request.getId(), workspace.getId());
        return SignupRequestView.of(request);
    }

    @Transactional
    public SignupRequestView reject(UUID requestId, UUID operatorId) {
        SignupRequest request = lockPending(requestId);
        request.reject(operatorId, Instant.now());
        requests.saveAndFlush(request);
        log.info("Sign-up {} rejected", request.getId());
        return SignupRequestView.of(request);
    }

    private SignupRequest lockPending(UUID requestId) {
        SignupRequest request = requests.lockById(requestId).orElseThrow(() ->
                new ApiErrorException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such sign-up request."));
        if (!request.isPending()) {
            throw new ApiErrorException(HttpStatus.CONFLICT, "SIGNUP_ALREADY_DECIDED",
                    "This sign-up request was already " + request.getStatus() + ".");
        }
        return request;
    }
}
