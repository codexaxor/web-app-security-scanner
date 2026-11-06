package com.company.secscan.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * M8.10 item 1 (D-093): no request path may fall back to the Default workspace. {@code Workspace.DEFAULT_ID} may
 * appear in main code ONLY in its declaration and the two startup bootstraps that seed Default-workspace users
 * (D-011 admin, D-091 demo). Any other use fails the build — a new one must take the principal's workspace
 * ({@code @CurrentWorkspace}) or a job's {@code workspace_id} instead.
 */
class DefaultWorkspaceUsageGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Set<String> ALLOWED = Set.of(
            "com/company/secscan/workspace/Workspace.java",
            "com/company/secscan/bootstrap/AdminBootstrap.java",
            "com/company/secscan/auth/DemoBootstrap.java");

    @Test
    void defaultIdIsUsedOnlyByItsDeclarationAndTheBootstraps() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ALLOWED.contains(MAIN.relativize(p).toString().replace('\\', '/')))
                    .filter(DefaultWorkspaceUsageGuardTest::mentionsDefaultId)
                    .map(p -> MAIN.relativize(p).toString())
                    .sorted().toList();
        }
        assertThat(offenders).as("Workspace.DEFAULT_ID outside the allowed files").isEmpty();
    }

    @Test
    void theGuardScansTheRealSources() throws IOException {
        // Sanity: the scan must actually see the allowed files, or an empty offender list proves nothing.
        assertThat(mentionsDefaultId(MAIN.resolve("com/company/secscan/workspace/Workspace.java"))).isTrue();
        assertThat(mentionsDefaultId(MAIN.resolve("com/company/secscan/bootstrap/AdminBootstrap.java"))).isTrue();
    }

    private static boolean mentionsDefaultId(Path file) {
        try {
            return Files.readString(file).contains("DEFAULT_ID");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
