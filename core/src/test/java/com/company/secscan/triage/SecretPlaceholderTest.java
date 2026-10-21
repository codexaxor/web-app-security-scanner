package com.company.secscan.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretPlaceholderTest {

    @Test
    void exampleFilesArePlaceholders() {
        assertThat(SecretPlaceholder.isPlaceholder("config/app.properties.example", "abcd")).isTrue();
        assertThat(SecretPlaceholder.isPlaceholder("deploy/.env.sample", "abcd")).isTrue();
        assertThat(SecretPlaceholder.isPlaceholder("k8s/secret.template", "abcd")).isTrue();
    }

    @Test
    void exampleSampleTemplateDirectoriesArePlaceholders() {
        assertThat(SecretPlaceholder.isPlaceholder("examples/app/config.yml", "abcd")).isTrue();
        assertThat(SecretPlaceholder.isPlaceholder("docs/sample/keys.txt", "abcd")).isTrue();
    }

    @Test
    void knownPlaceholderPreviewsAreMatched() {
        assertThat(SecretPlaceholder.isPlaceholder("src/App.java", "chan")).as("changeme").isTrue();
        assertThat(SecretPlaceholder.isPlaceholder("src/App.java", "EXAM")).as("example, case-insensitive").isTrue();
        assertThat(SecretPlaceholder.isPlaceholder("src/App.java", "****")).as("masked short value").isTrue();
    }

    @Test
    void realSecretsInRealPathsAreNotPlaceholders() {
        assertThat(SecretPlaceholder.isPlaceholder("src/main/resources/application.properties", "ghp_")).isFalse();
        assertThat(SecretPlaceholder.isPlaceholder("config/deploy-key.pem", "----")).isFalse();
    }
}
