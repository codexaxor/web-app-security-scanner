package com.company.secscan.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretPlaceholderTest {

    @Test
    void exampleFilesArePlaceholders() {
        assertThat(SecretPlaceholder.isPlaceholder("config/app.properties.example")).isTrue();
        assertThat(SecretPlaceholder.isPlaceholder("deploy/.env.sample")).isTrue();
        assertThat(SecretPlaceholder.isPlaceholder("k8s/secret.template")).isTrue();
    }

    @Test
    void exampleDirectoriesArePlaceholders() {
        assertThat(SecretPlaceholder.isPlaceholder("examples/app/config.yml")).isTrue();
        assertThat(SecretPlaceholder.isPlaceholder("example/keys.txt")).isTrue();
    }

    @Test
    void previewIsNeverConsulted() {
        // B1: a real secret whose masked preview happens to start chan/exam/plac, on a NORMAL path, is NOT a
        // placeholder — the decision is file-path-only and never looks at the preview.
        assertThat(SecretPlaceholder.isPlaceholder("src/App.java")).as("normal path, no preview arg").isFalse();
        assertThat(SecretPlaceholder.isPlaceholder("src/main/resources/application.properties")).isFalse();
        assertThat(SecretPlaceholder.isPlaceholder("config/deploy-key.pem")).isFalse();
    }

    @Test
    void narrowedDirsDoNotMatchTemplateOrSampleDirectories() {
        // S7: only *.example/*.sample/*.template SUFFIXES and example/examples DIRS are placeholders. A real key
        // under a Spring templates/ directory (a normal source location) stays visible.
        assertThat(SecretPlaceholder.isPlaceholder("src/main/resources/templates/x.html")).isFalse();
        assertThat(SecretPlaceholder.isPlaceholder("docs/sample/keys.txt")).as("sample/ dir no longer matches")
                .isFalse();
        assertThat(SecretPlaceholder.isPlaceholder("k8s/templates/secret.yaml")).isFalse();
    }
}
