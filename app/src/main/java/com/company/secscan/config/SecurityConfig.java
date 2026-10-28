package com.company.secscan.config;

import com.company.secscan.auth.SessionTimeoutFilter;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;
import org.springframework.boot.web.servlet.server.Session;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Security for the api process, split into two filter chains so the two authentication paths never interfere
 * (D-092):
 * <ol>
 *   <li><b>Basic chain</b> (order 1) — selected only for requests carrying an {@code Authorization: Basic}
 *       header: STATELESS (creates no session), CSRF disabled (no session cookie to protect), for curl/scripts.</li>
 *   <li><b>Browser chain</b> (order 2) — everything else: a server session (created at login), CSRF ON for
 *       unsafe requests (a session synchronizer token echoed in {@code X-CSRF-TOKEN}; {@code POST /auth/login} is
 *       exempt, {@code /auth/logout} is not), and the idle/absolute session lifetimes.</li>
 * </ol>
 * Both answer with the JSON 401 (no {@code WWW-Authenticate}, D-067(b)) — which is exactly what makes the Basic
 * chain safe to leave CSRF-free: a browser never silently replays Basic cross-site. Same-origin, no CORS (D-067).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");
    private static final RequestMatcher LOGIN = new AntPathRequestMatcher("/api/v1/auth/login", "POST");
    private static final String[] PUBLIC_PATHS = {"/actuator/health", "/actuator/health/**"};

    // D-092 lifetimes: idle (refreshed by any request, incl. the status-poll) and the absolute cap (login-age).
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(8);

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        // Session synchronizer token (server-side), delivered to the SPA only in the auth JSON bodies — never a
        // JS-readable cookie, so the D-067(e) document.cookie guard and the CSP stay unchanged.
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    /** The hardened session cookie (D-092): HttpOnly + Secure (overridable only for the e2e host) + SameSite=Strict. */
    @Bean
    WebServerFactoryCustomizer<ConfigurableWebServerFactory> sessionCookieCustomizer(
            @Value("${SECSCAN_SESSION_COOKIE_SECURE:true}") boolean secure) {
        return factory -> {
            if (factory instanceof AbstractServletWebServerFactory servlet) {
                Session session = servlet.getSession();
                Cookie cookie = session.getCookie();
                cookie.setName("SECSCAN_SESSION");
                cookie.setHttpOnly(true);
                cookie.setSecure(secure);
                cookie.setSameSite(Cookie.SameSite.STRICT);
                servlet.setSession(session);
            }
        };
    }

    @Bean
    @Order(1)
    SecurityFilterChain basicAuthChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint jsonUnauthorized = jsonUnauthorizedEntryPoint();
        http
                .securityMatcher(SecurityConfig::isHttpBasic)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(jsonUnauthorized))
                .httpBasic(basic -> basic.authenticationEntryPoint(jsonUnauthorized));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain browserChain(HttpSecurity http,
                                     SecurityContextRepository contextRepository,
                                     CsrfTokenRepository csrfTokenRepository) throws Exception {
        AuthenticationEntryPoint jsonUnauthorized = jsonUnauthorizedEntryPoint();
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Sign-in is reachable while unauthenticated; everything else (incl. /auth/session and
                        // /auth/logout) requires authentication.
                        .requestMatchers(LOGIN).permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(sc -> sc.securityContextRepository(contextRepository))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // Plain (non-XOR) handler so the raw token we hand the SPA in JSON matches the
                        // X-CSRF-TOKEN header it echoes back.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(csrfRequiredMatcher()))
                .exceptionHandling(e -> e.authenticationEntryPoint(jsonUnauthorized))
                // Enforce idle/absolute session lifetimes before the context is loaded (an expired session is
                // invalidated → the request is anonymous → JSON 401).
                .addFilterBefore(new SessionTimeoutFilter(IDLE_TIMEOUT, ABSOLUTE_TIMEOUT),
                        SecurityContextHolderFilter.class);
        return http.build();
    }

    /**
     * CSRF is required for an unsafe method on the browser chain, except {@code POST /auth/login} (the token is
     * issued by it). Safe methods are already excluded here; HTTP Basic never reaches this chain.
     */
    private static RequestMatcher csrfRequiredMatcher() {
        return request -> !SAFE_METHODS.contains(request.getMethod()) && !LOGIN.matches(request);
    }

    private static boolean isHttpBasic(jakarta.servlet.http.HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Basic ", 0, 6);
    }

    private static AuthenticationEntryPoint jsonUnauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"Authentication required.\"}");
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
