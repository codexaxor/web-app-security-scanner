package com.company.secscan.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the D-092 session lifetimes BEFORE the security context is loaded, so an expired session is
 * invalidated and the request proceeds anonymously (→ the JSON 401), never authenticated on a stale session:
 * <ul>
 *   <li><b>idle</b> ({@code idle}, default 30 min): time since the last request on this session. Every request
 *       refreshes it — so the status-poll (M8.8 item 5) keeps an active user signed in — EXCEPT the absolute cap
 *       below, which polling cannot extend.</li>
 *   <li><b>absolute</b> ({@code absolute}, default 8 h): time since the session was created at login,
 *       independent of activity.</li>
 * </ul>
 * Both are tracked as session timestamps (set at login via {@link #CREATED_AT}/{@link #LAST_ACCESS}) so the
 * behaviour is deterministic and container-independent. Only session-bearing requests are affected; stateless
 * HTTP Basic requests carry no session and pass straight through.
 */
public class SessionTimeoutFilter extends OncePerRequestFilter {

    public static final String CREATED_AT = "secscan.session.createdAt";
    public static final String LAST_ACCESS = "secscan.session.lastAccess";

    private final long idleMillis;
    private final long absoluteMillis;

    public SessionTimeoutFilter(Duration idle, Duration absolute) {
        this.idleMillis = idle.toMillis();
        this.absoluteMillis = absolute.toMillis();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            long now = System.currentTimeMillis();
            long createdAt = asLong(session.getAttribute(CREATED_AT), session.getCreationTime());
            Long lastAccess = (Long) session.getAttribute(LAST_ACCESS);
            boolean idleExpired = lastAccess != null && (now - lastAccess) > idleMillis;
            boolean absoluteExpired = (now - createdAt) > absoluteMillis;
            if (idleExpired || absoluteExpired) {
                session.invalidate();
            } else {
                session.setAttribute(LAST_ACCESS, now);
            }
        }
        chain.doFilter(request, response);
    }

    private static long asLong(Object value, long fallback) {
        return value instanceof Long l ? l : fallback;
    }
}
