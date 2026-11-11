package com.company.secscan.auth;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated user for BOTH paths (JSON session login and HTTP Basic, D-092): who they are, which workspace
 * every request of theirs is scoped to (D-093), and whether they are the platform operator (D-094 →
 * {@code ROLE_OPERATOR}). Account status is checked after the password (see {@link SecscanAuthenticationProvider})
 * and re-checked per request (see {@link ActiveAccountFilter}), so {@link #isEnabled()} is always true here —
 * Spring's own pre-authentication status check would otherwise reveal the status before the password is verified.
 */
public final class SecscanPrincipal implements UserDetails, CredentialsContainer {

    public static final String ROLE_OPERATOR = "ROLE_OPERATOR";

    private final UUID userId;
    private final UUID workspaceId;
    private final String email;
    private String passwordHash;
    private final boolean operator;
    private final boolean active;

    public SecscanPrincipal(UUID userId, UUID workspaceId, String email, String passwordHash, boolean operator,
                            boolean active) {
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.operator = operator;
        this.active = active;
    }

    public UUID userId() {
        return userId;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public boolean operator() {
        return operator;
    }

    /** The status at load time; the provider refuses an inactive account only after the password matched. */
    public boolean active() {
        return active;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return operator ? List.of(new SimpleGrantedAuthority(ROLE_OPERATOR)) : List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;   // never keep the hash in the session-held principal
    }

    @Override
    public String toString() {
        return "SecscanPrincipal[" + email + "]";   // never the hash
    }
}
