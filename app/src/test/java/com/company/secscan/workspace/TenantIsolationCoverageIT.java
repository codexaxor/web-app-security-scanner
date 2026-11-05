package com.company.secscan.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractApiServerIT;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * M8.10 item 7: every {@code /api/v1} endpoint must have a tenant-isolation case. Enumerates the LIVE handler
 * mappings and fails the build if one is neither in {@link TenantIsolationIT#CASES} nor explicitly
 * {@link TenantIsolationIT#EXEMPT} (with a reason) — so a new endpoint without an isolation case cannot land.
 * {@code /api/v1/auth/**} (pre-authentication) and {@code /api/v1/admin/**} (operator-only, every mapping checked
 * by {@code OperatorAccessIT}) are out of scope. Stale entries (no such mapping any more) also fail.
 */
class TenantIsolationCoverageIT extends AbstractApiServerIT {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private Set<String> tenantMappings() {
        Set<String> out = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/v1/") || pattern.startsWith("/api/v1/auth/")
                        || pattern.startsWith("/api/v1/admin/") || pattern.startsWith("/api/v1/__test__")) {
                    continue;
                }
                info.getMethodsCondition().getMethods().forEach(m -> out.add(m.name() + " " + pattern));
            }
        });
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
