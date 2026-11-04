package com.company.secscan.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Test-only HTTP helper for {@link AbstractApiServerIT} subclasses (M8.10): a browser-like session (JSON login →
 * session cookie + CSRF token) or a stateless HTTP Basic caller, both driving the real servlet container.
 */
public final class HttpSession {

    private final TestRestTemplate rest;
    private final String baseUrl;
    private final String cookie;
    private final String csrfToken;
    private final String basic;

    private HttpSession(TestRestTemplate rest, String baseUrl, String cookie, String csrfToken, String basic) {
        this.rest = rest;
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.csrfToken = csrfToken;
        this.basic = basic;
    }

    /**
     * A client that never retries. The default (Apache HttpClient 5) retry strategy honours {@code Retry-After} on a
     * 429 by SLEEPING and retrying — up to the full 15-minute window — so rate-limit tests must use this one.
     */
    public static TestRestTemplate noRetryClient() {
        return new TestRestTemplate(new org.springframework.boot.web.client.RestTemplateBuilder()
                .requestFactory(org.springframework.http.client.SimpleClientHttpRequestFactory::new));
    }

    /** Raw JSON login (no assertion on the outcome). */
    public static ResponseEntity<String> login(TestRestTemplate rest, int port, String username, String password) {
        return postJson(rest, "http://localhost:" + port + "/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", new HttpHeaders());
    }

    /** A signed-in browser session; fails if the login is not a 200. */
    public static HttpSession browser(TestRestTemplate rest, int port, String username, String password) {
        ResponseEntity<String> res = login(rest, port, username, password);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("login failed: " + res.getStatusCode() + " " + res.getBody());
        }
        return new HttpSession(rest, "http://localhost:" + port, sessionCookie(res), field(res.getBody(),
                "csrfToken"), null);
    }

    /** A stateless HTTP Basic caller. */
    public static HttpSession basic(TestRestTemplate rest, int port, String username, String password) {
        String header = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new HttpSession(rest, "http://localhost:" + port, null, null, header);
    }

    public ResponseEntity<String> get(String path) {
        return rest.exchange(baseUrl + path, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
    }

    public ResponseEntity<String> send(HttpMethod method, String path, String jsonBody) {
        HttpHeaders h = headers();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(baseUrl + path, method, new HttpEntity<>(jsonBody == null ? "{}" : jsonBody, h),
                String.class);
    }

    public ResponseEntity<String> post(String path, String jsonBody) {
        return send(HttpMethod.POST, path, jsonBody);
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        if (cookie != null) {
            h.add(HttpHeaders.COOKIE, cookie);
            h.add("X-CSRF-TOKEN", csrfToken);
        }
        if (basic != null) {
            h.add(HttpHeaders.AUTHORIZATION, basic);
        }
        return h;
    }

    public static ResponseEntity<String> postJson(TestRestTemplate rest, String url, String body,
                                                  HttpHeaders extra) {
        HttpHeaders h = new HttpHeaders();
        h.addAll(extra);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    public static String sessionCookie(ResponseEntity<?> response) {
        for (String setCookie : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
            if (setCookie.startsWith("SECSCAN_SESSION=")) {
                return setCookie.split(";", 2)[0];
            }
        }
        return null;
    }

    /** Minimal test-only extraction of a top-level string field from a JSON body. */
    public static String field(String json, String name) {
        int i = json == null ? -1 : json.indexOf("\"" + name + "\":\"");
        if (i < 0) {
            return null;
        }
        int start = i + name.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }
}
