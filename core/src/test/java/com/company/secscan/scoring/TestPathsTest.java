package com.company.secscan.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestPathsTest {

    @Test
    void recognizesTestDirectoriesAndFileNames() {
        assertThat(TestPaths.isTestPath("src/test/java/app/FooTest.java")).isTrue();
        assertThat(TestPaths.isTestPath("app/tests/helper.py")).isTrue();
        assertThat(TestPaths.isTestPath("src/main/java/app/FooTest.java")).isTrue(); // *Test.java basename
        assertThat(TestPaths.isTestPath("web/src/Button.spec.ts")).isTrue();
        assertThat(TestPaths.isTestPath("web/src/Button.test.tsx")).isTrue();
        assertThat(TestPaths.isTestPath("pkg/handler_test.go")).isTrue();
        assertThat(TestPaths.isTestPath("app/models/user_spec.rb")).isTrue();
        assertThat(TestPaths.isTestPath("Services/AdminTests.cs")).isTrue();
    }

    @Test
    void doesNotFlagProductionPaths() {
        assertThat(TestPaths.isTestPath("src/main/java/app/SecurityConfig.java")).isFalse();
        assertThat(TestPaths.isTestPath("web/src/Button.tsx")).isFalse();
        assertThat(TestPaths.isTestPath("Services/AdminController.cs")).isFalse();
        assertThat(TestPaths.isTestPath("latest/greatest.go")).isFalse(); // "latest" is not a test segment
        assertThat(TestPaths.isTestPath(null)).isFalse();
        assertThat(TestPaths.isTestPath("")).isFalse();
    }
}
