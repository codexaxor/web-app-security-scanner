package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * D-092 over a real servlet container: login/logout/session, the session cookie flags (HttpOnly + Secure +
 * SameSite=Strict), session-id rotation on login, CSRF on cookie-authenticated unsafe requests, a JSON-only
 * contract (415), and HTTP Basic still working statelessly. The JSON 401 carries no {@code WWW-Authenticate}
 * (D-067(b)) — which is what keeps Basic safe to exempt from CSRF.
 */
class AuthSessionIT extends AbstractApiServerIT {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    AppUserRepository appUsers;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seedAdmin() {
        appUsers.save(new AppUser(Workspace.DEFAULT_ID, "admin@test.local", encoder.encode("correct-horse"),
                "admin"));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static String sessionCookie(ResponseEntity<?> response) {
        for (String setCookie : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
            if (setCookie.startsWith("SECSCAN_SESSION=")) {
                return setCookie.split(";", 2)[0];   // "SECSCAN_SESSION=<id>"
            }
        }
        return null;
    }

    private ResponseEntity<String> login(String identifier, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + identifier + "\",\"password\":\"" + password + "\"}";
        return rest.exchange(url("/api/v1/auth/login"), HttpMethod.POST, new HttpEntity<>(body, headers),
                String.class);
    }

    private static String csrfToken(String loginBody) {
        // minimal, test-only extraction of the csrfToken field from the JSON body
        int i = loginBody.indexOf("\"csrfToken\":\"");
        if (i < 0) {
            return null;
        }
        int start = i + "\"csrfToken\":\"".length();
        return loginBody.substring(start, loginBody.indexOf('"', start));
    }

    @Test
    void loginSetsAHardenedSessionCookieAndReturnsTheSessionInfo() {
        ResponseEntity<String> res = login("admin@test.local", "correct-horse");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setCookie = res.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of()).stream()
                .filter(c -> c.startsWith("SECSCAN_SESSION=")).findFirst().orElseThrow();
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("Secure");
        assertThat(setCookie).containsIgnoringCase("SameSite=Strict");
        assertThat(res.getBody()).contains("\"username\":\"admin@test.local\"");
        assertThat(res.getBody()).contains("\"demoMode\":false");
        assertThat(res.getBody()).contains("\"csrfToken\":\"");
    }

    @Test
    void theSessionIdRotatesOnLogin() {
        ResponseEntity<String> first = login("admin@test.local", "correct-horse");
        String cookieA = sessionCookie(first);
        assertThat(cookieA).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, cookieA);
        ResponseEntity<String> second = rest.exchange(url("/api/v1/auth/login"), HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin@test.local\",\"password\":\"correct-horse\"}", headers),
                String.class);
        String cookieB = sessionCookie(second);

        assertThat(cookieB).isNotNull();
        assertThat(cookieB).isNotEqualTo(cookieA);   // fixation protection: a fresh id on login
    }

    @Test
    void wrongPasswordIsA401() {
        assertThat(login("admin@test.local", "nope").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void formEncodedLoginIsA415() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> res = rest.exchange(url("/api/v1/auth/login"), HttpMethod.POST,
                new HttpEntity<>("username=admin@test.local&password=correct-horse", headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void sessionEndpointReflectsTheSignedInUserThenReturns401AfterLogout() {
        ResponseEntity<String> loginRes = login("admin@test.local", "correct-horse");
        String cookie = sessionCookie(loginRes);
        String token = csrfToken(loginRes.getBody());

        HttpHeaders withCookie = new HttpHeaders();
        withCookie.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<String> session = rest.exchange(url("/api/v1/auth/session"), HttpMethod.GET,
                new HttpEntity<>(withCookie), String.class);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(session.getBody()).contains("\"username\":\"admin@test.local\"");

        // logout WITHOUT the CSRF token → 403 (it is an unsafe cookie request).
        HttpHeaders logoutNoToken = new HttpHeaders();
        logoutNoToken.setContentType(MediaType.APPLICATION_JSON);
        logoutNoToken.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<String> forbidden = rest.exchange(url("/api/v1/auth/logout"), HttpMethod.POST,
                new HttpEntity<>("{}", logoutNoToken), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // logout WITH the token → 204, and the session is then gone.
        HttpHeaders logout = new HttpHeaders();
        logout.setContentType(MediaType.APPLICATION_JSON);
        logout.add(HttpHeaders.COOKIE, cookie);
        logout.add("X-CSRF-TOKEN", token);
        ResponseEntity<String> loggedOut = rest.exchange(url("/api/v1/auth/logout"), HttpMethod.POST,
                new HttpEntity<>("{}", logout), String.class);
        assertThat(loggedOut.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> afterLogout = rest.exchange(url("/api/v1/auth/session"), HttpMethod.GET,
                new HttpEntity<>(withCookie), String.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void httpBasicStillWorksStatelesslyWithNoSessionCookie() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                .encodeToString("admin@test.local:correct-horse".getBytes(StandardCharsets.UTF_8)));
        ResponseEntity<String> res = rest.exchange(url("/api/v1/repositories"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessionCookie(res)).isNull();   // Basic creates no session
    }

    @Test
    void unauthenticatedIsAJson401WithoutWwwAuthenticate() {
        ResponseEntity<String> res = rest.exchange(url("/api/v1/repositories"), HttpMethod.GET,
                HttpEntity.EMPTY, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getHeaders().containsKey(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
        assertThat(res.getBody()).contains("\"error\":\"UNAUTHORIZED\"");
    }
}
