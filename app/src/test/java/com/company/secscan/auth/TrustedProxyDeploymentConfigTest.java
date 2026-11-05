package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * M8.10 S6b (D-098): the deployment files that decide which client IP the rate limits see. The ui nginx must
 * OVERWRITE X-Forwarded-For with its direct peer (an appended client value would let anyone pick their own rate
 * key), and every compose stack must pin its own subnet and trust only that subnet. Behaviour of the resolver
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
    void defaultStackPinsItsSubnetAndTrustsOnlyIt() throws IOException {
        String compose = read("docker-compose.yml");
        assertThat(compose).contains("- subnet: ${SECSCAN_COMPOSE_SUBNET:-10.203.0.0/24}");
        assertThat(compose).contains(
                "SECSCAN_TRUSTED_PROXIES: ${SECSCAN_TRUSTED_PROXIES:-${SECSCAN_COMPOSE_SUBNET:-10.203.0.0/24}}");
    }

    @Test
    void smokeAndE2eStacksUseTheirOwnSubnets() throws IOException {
        String smoke = read("scripts/compose-smoke.sh");
        assertThat(smoke).contains("export SECSCAN_COMPOSE_SUBNET=\"${SECSCAN_SMOKE_SUBNET:-10.203.1.0/24}\"");
        assertThat(smoke).contains("export SECSCAN_TRUSTED_PROXIES=\"$SECSCAN_COMPOSE_SUBNET\"");

        String e2e = read("ui/e2e/docker-compose.e2e.yml");
        assertThat(e2e).contains("- subnet: ${SECSCAN_E2E_SUBNET:-10.203.2.0/24}");
        assertThat(e2e).contains("SECSCAN_TRUSTED_PROXIES: ${SECSCAN_E2E_SUBNET:-10.203.2.0/24}");
    }
}
