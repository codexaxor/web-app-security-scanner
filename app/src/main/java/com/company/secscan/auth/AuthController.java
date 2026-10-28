package com.company.secscan.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Browser session endpoints (D-092). A JSON {@code POST /login} authenticates against {@code app_user}, creates
 * a server session (cookie set by the container: HttpOnly + Secure + SameSite=Strict) and rotates the session id;
 * {@code POST /logout} invalidates it (and, being an unsafe cookie request, requires the CSRF token);
 * {@code GET /session} reports the current user, the demo-mode flag and the CSRF token. The CSRF token is a
 * session synchronizer token delivered only in these JSON bodies — never a JS-readable cookie (so the D-067(e)
 * guard and the CSP are unchanged) — and echoed by the SPA in the {@code X-CSRF-TOKEN} header. The password is
 * never logged and never returned. HTTP Basic remains the stateless path for curl/scripts (SecurityConfig).
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** What the SPA needs after login / on load: who is signed in, whether this is a demo host, and the token. */
    public record SessionInfo(String username, boolean demoMode, String csrfToken) {
    }

    public record LoginRequest(String username, String password) {
    }

    private final AuthenticationManager authManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final DemoMode demoMode;

    public AuthController(AuthenticationManager authManager,
                          SecurityContextRepository securityContextRepository,
                          CsrfTokenRepository csrfTokenRepository,
                          DemoMode demoMode) {
        this.authManager = authManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.demoMode = demoMode;
    }

    /**
     * JSON-only ({@code consumes} → 415 for a form-encoded or untyped body). Authenticates, rotates the session
     * id (fixation protection), persists the security context to the new session and returns a fresh CSRF token.
     * A bad credential (or a demo-locked-out identifier) is a 401 with no detail.
     */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionInfo> login(@RequestBody LoginRequest body,
                                             HttpServletRequest request, HttpServletResponse response) {
        String username = body == null || body.username() == null
                ? "" : body.username().trim().toLowerCase(Locale.ROOT);
        String password = body == null ? null : body.password();

        Authentication authentication;
        try {
            authentication = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
        } catch (AuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong email or password.");
        }

        // Fixation protection: drop any pre-login session and start a fresh one, so the id changes on login.
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession session = request.getSession(true);
        long now = System.currentTimeMillis();
        session.setAttribute(SessionTimeoutFilter.CREATED_AT, now);
        session.setAttribute(SessionTimeoutFilter.LAST_ACCESS, now);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        String token = freshCsrfToken(request, response);
        return ResponseEntity.ok(new SessionInfo(authentication.getName(), demoMode.enabled(), token));
    }

    /** JSON-only + CSRF-protected (enforced by the CsrfFilter): invalidates the session and clears the cookie. */
    @PostMapping(path = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /** The current session (401 if none). Returns the CSRF token so a reloaded SPA can resume unsafe requests. */
    @GetMapping("/session")
    public SessionInfo session(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in.");
        }
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String value = token != null ? token.getToken() : freshCsrfToken(request, response);
        return new SessionInfo(authentication.getName(), demoMode.enabled(), value);
    }

    private String freshCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(token, request, response);
        return token.getToken();
    }
}
