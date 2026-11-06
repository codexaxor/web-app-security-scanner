package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * M8.10 S6b (D-098): the deployment files that decide which client IP the rate limits see. The ui nginx must
 * OVERWRITE X-Forwarded-For with its direct peer (an appended client value would let anyone pick their own rate
 * key), and every compose stack must pin its own subnet and trust only its nginx's pinned address (review SF-3). Behaviour of the resolver
 * itself is proven by {@code ClientIpResolverTest} / {@code ClientIpSpoofIT}.
 */
class TrustedProxyDeploymentConfigTest {

    private static final Path ROOT = Path.of("..");

    private static String read(String path) throws IOException {
        return Files.readString(ROOT.resolve(path));
    }

    @Test
    void nginxOverwritesForwardedForWithTheDirectPeer() throws IOException {
        String nginx = read("ui/nginx.conf");
        assertThat(nginx).contains("proxy_set_header X-Forwarded-For $remote_addr;");
        assertThat(nginx).doesNotContain("$proxy_add_x_forwarded_for");
    }

    @Test
    void defaultStackPinsNginxAndTrustsOnlyThatAddress() throws IOException {
        String compose = read("docker-compose.yml");
        assertThat(compose).contains("- subnet: ${SECSCAN_COMPOSE_SUBNET:-10.203.0.0/24}");
        assertThat(compose).contains("ipv4_address: ${SECSCAN_UI_IP:-10.203.0.10}");
        assertThat(compose).contains(
                "SECSCAN_TRUSTED_PROXIES: ${SECSCAN_TRUSTED_PROXIES:-${SECSCAN_UI_IP:-10.203.0.10}}");
        assertThat(trustedProxiesLine(compose)).doesNotContain("/24").doesNotContain("SUBNET");
    }

    @Test
    void smokeAndE2eStacksUseTheirOwnSubnetsAndTrustOnlyTheirNginx() throws IOException {
        String smoke = read("scripts/compose-smoke.sh");
        assertThat(smoke).contains("export SECSCAN_COMPOSE_SUBNET=\"${SECSCAN_SMOKE_SUBNET:-10.203.1.0/24}\"");
        assertThat(smoke).contains("export SECSCAN_UI_IP=\"${SECSCAN_SMOKE_UI_IP:-10.203.1.10}\"");
        assertThat(smoke).contains("export SECSCAN_TRUSTED_PROXIES=\"$SECSCAN_UI_IP\"");

        String e2e = read("ui/e2e/docker-compose.e2e.yml");
        assertThat(e2e).contains("- subnet: ${SECSCAN_E2E_SUBNET:-10.203.2.0/24}");
        assertThat(e2e).contains("ipv4_address: ${SECSCAN_E2E_UI_IP:-10.203.2.10}");
        assertThat(e2e).contains("SECSCAN_TRUSTED_PROXIES: ${SECSCAN_E2E_UI_IP:-10.203.2.10}");
        assertThat(trustedProxiesLine(e2e)).doesNotContain("/24").doesNotContain("SUBNET");
    }

    private static String trustedProxiesLine(String file) {
        return file.lines().filter(l -> l.trim().startsWith("SECSCAN_TRUSTED_PROXIES:")).findFirst().orElseThrow();
    }
}
