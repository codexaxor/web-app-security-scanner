package com.company.secscan.signup;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.support.HttpSession;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * D-094 (resolves OD-9): EVERY {@code /api/v1/admin/**} mapping — enumerated from the live handler mapping, so a
 * new admin endpoint is covered automatically — answers 403 to a signed-in non-operator (session and Basic alike)
 * and 401 to an anonymous caller. The operator gets through.
 */
class OperatorAccessIT extends AbstractApiServerIT {

    static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    AppUserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    record Endpoint(HttpMethod method, String path) {
    }

    @BeforeEach
    void seed() {
        AppUser operator = new AppUser(Workspace.DEFAULT_ID, "operator@test.local", encoder.encode(PASSWORD), "admin");
        operator.promoteToOperator();
        users.save(operator);
        users.save(new AppUser(Workspace.DEFAULT_ID, "member@test.local", encoder.encode(PASSWORD), "admin"));
    }

    @Test
    void everyAdminEndpointIsForbiddenToANonOperatorSession() {
        HttpSession member = HttpSession.browser(rest, port, "member@test.local", PASSWORD);
        for (Endpoint e : adminEndpoints()) {
            assertThat(member.send(e.method(), e.path(), "{}").getStatusCode()).as("%s %s", e.method(), e.path())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void everyAdminEndpointIsForbiddenToANonOperatorBasicCaller() {
        HttpSession member = HttpSession.basic(rest, port, "member@test.local", PASSWORD);
        for (Endpoint e : adminEndpoints()) {
            assertThat(member.send(e.method(), e.path(), "{}").getStatusCode()).as("%s %s", e.method(), e.path())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void everyAdminEndpointIsA401ForAnAnonymousCaller() {
        for (Endpoint e : adminEndpoints()) {
            var res = rest.exchange("http://localhost:" + port + e.path(), e.method(), HttpEntity.EMPTY,
                    String.class);
            assertThat(res.getStatusCode()).as("%s %s", e.method(), e.path()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void theOperatorIsAdmittedBySessionAndBasic() {
        assertThat(HttpSession.browser(rest, port, "operator@test.local", PASSWORD).get("/api/v1/admin/sign-ups")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(HttpSession.basic(rest, port, "operator@test.local", PASSWORD).get("/api/v1/admin/sign-ups")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theEnumerationSeesTheKnownAdminEndpoints() {
        assertThat(adminEndpoints()).extracting(Endpoint::path).contains(
                "/api/v1/admin/intel/kev/sync", "/api/v1/admin/maintenance/retire-ignored-orphans",
                "/api/v1/admin/sign-ups", "/api/v1/admin/users");
    }

    private List<Endpoint> adminEndpoints() {
        List<Endpoint> out = new ArrayList<>();
        mappings.getHandlerMethods().keySet().forEach(info -> {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/v1/admin")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                var methods = info.getMethodsCondition().getMethods();
                for (RequestMethod m : methods.isEmpty() ? List.of(RequestMethod.GET) : methods) {
                    out.add(new Endpoint(HttpMethod.valueOf(m.name()), path));
                }
            }
        });
        assertThat(out).isNotEmpty();
        return out;
    }
}
