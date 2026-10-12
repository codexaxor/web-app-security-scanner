package com.company.secscan.scan.worker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Worker-only wiring: the Docker client (talks to the host daemon via the mounted socket; D-026) and the
 * scanner properties. Nothing here loads in the api process.
 */
@Configuration
@Profile("worker")
@EnableConfigurationProperties(ScannerProperties.class)
public class WorkerScanConfig {

    /**
     * Docker client over the daemon socket (from {@code DOCKER_HOST}, default {@code unix:///var/run/docker.sock}).
     * The socket is mounted only into the worker, never into a scanner container (D-026 / CLAUDE.md).
     */
    @Bean(destroyMethod = "close")
    public DockerClient dockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(16)
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofMinutes(35))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
