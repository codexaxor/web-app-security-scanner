package com.company.secscan.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractApiServerIT;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * M8.10 item 7: every {@code /api/v1} endpoint must have a tenant-isolation case. Enumerates the LIVE handler
 * mappings and fails the build if one is neither in {@link TenantIsolationIT#CASES} nor explicitly
 * {@link TenantIsolationIT#EXEMPT} (with a reason) — so a new endpoint without an isolation case cannot land.
 * {@code /api/v1/auth/**} (pre-authentication) and {@code /api/v1/admin/**} (operator-only, every mapping checked
 * by {@code OperatorAccessIT}) are out of scope. Stale entries (no such mapping any more) also fail. A mapping with
 * no method condition counts as every method (review SF-5).
 */
class TenantIsolationCoverageIT extends AbstractApiServerIT {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private Set<String> tenantMappings() {
        Set<String> out = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> out.addAll(keysFor(info)));
        return out;
    }

    /** "METHOD pattern" keys of one tenant-scoped mapping (none for auth/admin/test paths). */
    static Set<String> keysFor(RequestMappingInfo info) {
        Set<String> out = new TreeSet<>();
        // Review SF-5: a mapping with NO method condition answers every method — enumerate them all, so an
        // uncovered bare @RequestMapping fails instead of being skipped.
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods().isEmpty()
                ? EnumSet.allOf(RequestMethod.class) : info.getMethodsCondition().getMethods();
        for (String pattern : info.getPatternValues()) {
            if (!pattern.startsWith("/api/v1/") || pattern.startsWith("/api/v1/auth/")
                    || pattern.startsWith("/api/v1/admin/") || pattern.startsWith("/api/v1/__test__")) {
                continue;
            }
            methods.forEach(m -> out.add(m.name() + " " + pattern));
        }
        return out;
    }

    @Test
    void everyTenantEndpointHasAnIsolationCaseOrAReasonedExemption() {
        Set<String> uncovered = new TreeSet<>(tenantMappings());
        uncovered.removeAll(TenantIsolationIT.coveredMappings());
        uncovered.removeAll(TenantIsolationIT.EXEMPT.keySet());
        assertThat(uncovered).as("endpoints without a TenantIsolationIT case or exemption").isEmpty();
    }

    @Test
    void aMappingWithoutAMethodConditionCountsAsEveryMethod() {
        var info = RequestMappingInfo.paths("/api/v1/probe").build();
        assertThat(info.getMethodsCondition().getMethods()).isEmpty();
        assertThat(keysFor(info)).contains("GET /api/v1/probe", "POST /api/v1/probe", "DELETE /api/v1/probe")
                .hasSize(RequestMethod.values().length);
    }

    @Test
    void noStaleCasesOrExemptions() {
        Set<String> live = tenantMappings();
        assertThat(live).as("sanity: mappings were found").hasSizeGreaterThan(20);
        Set<String> declared = new TreeSet<>(TenantIsolationIT.coveredMappings());
        declared.addAll(TenantIsolationIT.EXEMPT.keySet());
        declared.removeAll(live);
        assertThat(declared).as("cases/exemptions for mappings that no longer exist").isEmpty();
        assertThat(TenantIsolationIT.EXEMPT.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }
}
