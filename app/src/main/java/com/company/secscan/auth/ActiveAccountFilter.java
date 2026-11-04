package com.company.secscan.auth;

import com.company.secscan.user.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-checks the signed-in user's status on every authenticated request (D-098): a user disabled by the operator
 * loses a live session on its very next request (session invalidated → the JSON 401), not after the 8 h cap.
 * One primary-key lookup by the principal's own user id — part of the authentication trust root (CLAUDE.md
 * rule 1), like the login lookup by email.
 */
public class ActiveAccountFilter extends OncePerRequestFilter {

    private final AppUserRepository users;
    private final AuthenticationEntryPoint unauthorized;

    public ActiveAccountFilter(AppUserRepository users, AuthenticationEntryPoint unauthorized) {
        this.users = users;
        this.unauthorized = unauthorized;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SecscanPrincipal principal) {
            boolean active = users.findById(principal.userId()).map(u -> u.isActive()).orElse(false);
            if (!active) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
                unauthorized.commence(request, response,
                        new InsufficientAuthenticationException("Account no longer active."));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
