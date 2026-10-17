#!/usr/bin/env python3
"""Unit tests for the static Gradle resolver (gradle_resolver.py), the D-039 gate.

Offline, stdlib ``unittest`` only. Wired into ``./gradlew build`` via the ``gradleResolverTest`` task
(app/build.gradle.kts), so a resolver regression fails the DEFAULT build. The behavioural end-to-end proof
(real transitive CVEs via OSV) is the live-osv ``GradleResolverIT``.

Run directly: ``python3 gradle_resolver_test.py`` (NOT ``-I`` -- the harness imports the resolver module from
this directory; ``-I`` is for the container invocation against untrusted repos, not for our own test harness).
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gradle_resolver as gr  # noqa: E402

RESOLVER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gradle_resolver.py")


def write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return path


def resolve(root, rel):
    """Concrete coordinates the resolver extracts from one build file in ``root``."""
    libraries, bundles = gr.load_version_catalog(root)
    return gr.resolve_build_file(root, os.path.join(root, rel), libraries, bundles)


class StringNotationTests(unittest.TestCase):
    def test_groovy_single_and_double_quotes(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation 'com.google.guava:guava:30.1-jre'
                    api "org.apache.commons:commons-text:1.9"
                    runtimeOnly 'org.postgresql:postgresql:42.3.1'
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertIn(("com.google.guava", "guava", "30.1-jre"), coords)
        self.assertIn(("org.apache.commons", "commons-text", "1.9"), coords)
        self.assertIn(("org.postgresql", "postgresql", "42.3.1"), coords)

    def test_kotlin_paren_notation(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle.kts", """
                dependencies {
                    implementation("com.google.guava:guava:30.1-jre")
                    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
                }
            """)
            coords = resolve(root, "build.gradle.kts")
        self.assertEqual(coords, [("com.google.guava", "guava", "30.1-jre")])

    def test_platform_bom_is_unwrapped(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation platform('org.springframework.boot:spring-boot-dependencies:3.2.0')
                    implementation enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.15.0")
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertIn(("org.springframework.boot", "spring-boot-dependencies", "3.2.0"), coords)
        self.assertIn(("com.fasterxml.jackson", "jackson-bom", "2.15.0"), coords)

    def test_map_notation(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation group: 'commons-io', name: 'commons-io', version: '2.6'
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertEqual(coords, [("commons-io", "commons-io", "2.6")])

    def test_dependency_with_trailing_closure(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation('org.apache.commons:commons-text:1.9') {
                        exclude group: 'org.apache.commons', module: 'commons-lang3'
                    }
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertEqual(coords, [("org.apache.commons", "commons-text", "1.9")])


class ExclusionTests(unittest.TestCase):
    def test_test_scope_is_excluded(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation 'com.google.guava:guava:30.1-jre'
                    testImplementation 'org.mockito:mockito-core:5.0.0'
                    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.0'
                    integrationTestImplementation 'io.rest-assured:rest-assured:5.3.0'
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertEqual(coords, [("com.google.guava", "guava", "30.1-jre")])

    def test_non_dependency_lines_ignored(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies {
                    implementation project(':core')
                    implementation files('libs/local.jar')
                    implementation gradleApi()
                    implementation 'com.google.guava:guava:30.1-jre'
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertEqual(coords, [("com.google.guava", "guava", "30.1-jre")])


class VersionResolutionTests(unittest.TestCase):
    def test_non_concrete_versions_skipped_not_guessed(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation 'g:dynamic:1.2.+'
                    implementation 'g:latest:latest.release'
                    implementation 'g:range:[1.0,2.0)'
                    implementation 'g:unresolved:$missingVersion'
                    implementation 'g:noversion'
                    implementation 'g:concrete:1.2.3'
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertEqual(coords, [("g", "concrete", "1.2.3")])

    def test_property_interpolation_from_gradle_properties(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "gradle.properties", "springVersion=6.1.0\n")
            write(root, "build.gradle", """
                dependencies {
                    implementation "org.springframework:spring-web:$springVersion"
                    implementation "org.springframework:spring-core:${springVersion}"
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertIn(("org.springframework", "spring-web", "6.1.0"), coords)
        self.assertIn(("org.springframework", "spring-core", "6.1.0"), coords)

    def test_in_file_variable_definitions(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                def guavaVersion = '30.1-jre'
                ext.jacksonVersion = '2.15.0'
                dependencies {
                    implementation "com.google.guava:guava:$guavaVersion"
                    implementation "com.fasterxml.jackson.core:jackson-databind:${jacksonVersion}"
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertIn(("com.google.guava", "guava", "30.1-jre"), coords)
        self.assertIn(("com.fasterxml.jackson.core", "jackson-databind", "2.15.0"), coords)

    def test_kotlin_val_variable(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle.kts", """
                val ktorVersion = "2.3.0"
                dependencies {
                    implementation("io.ktor:ktor-client-core:$ktorVersion")
                }
            """)
            coords = resolve(root, "build.gradle.kts")
        self.assertEqual(coords, [("io.ktor", "ktor-client-core", "2.3.0")])


class VersionCatalogTests(unittest.TestCase):
    CATALOG = """
        [versions]
        guava = "30.1-jre"
        jackson = "2.15.0"

        [libraries]
        guava = { module = "com.google.guava:guava", version.ref = "guava" }
        commons-text = { group = "org.apache.commons", name = "commons-text", version = "1.9" }
        inline = "org.postgresql:postgresql:42.3.1"
        jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }

        [bundles]
        jackson = ["jackson-databind"]
    """

    def test_catalog_accessors(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "gradle/libs.versions.toml", self.CATALOG)
            write(root, "build.gradle.kts", """
                dependencies {
                    implementation(libs.guava)
                    implementation(libs.commons.text)
                    implementation(libs.inline)
                }
            """)
            coords = resolve(root, "build.gradle.kts")
        self.assertIn(("com.google.guava", "guava", "30.1-jre"), coords)
        self.assertIn(("org.apache.commons", "commons-text", "1.9"), coords)
        self.assertIn(("org.postgresql", "postgresql", "42.3.1"), coords)

    def test_bundle_expands_to_members(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "gradle/libs.versions.toml", self.CATALOG)
            write(root, "build.gradle.kts", """
                dependencies { implementation(libs.bundles.jackson) }
            """)
            coords = resolve(root, "build.gradle.kts")
        self.assertEqual(coords, [("com.fasterxml.jackson.core", "jackson-databind", "2.15.0")])

    def test_version_accessor_is_not_a_dependency(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "gradle/libs.versions.toml", self.CATALOG)
            write(root, "build.gradle.kts", """
                dependencies { implementation("g:a:" + libs.versions.guava.get()) }
            """)
            coords = resolve(root, "build.gradle.kts")
        # libs.versions.* is a version accessor, not a library; nothing concrete is declared here.
        self.assertEqual(coords, [])


class WalkAndOutputTests(unittest.TestCase):
    def test_build_file_with_lockfile_is_skipped(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", "dependencies { implementation 'g:a:1.0' }\n")
            write(root, "gradle.lockfile", "# locked\n")
            self.assertEqual(gr.find_build_files(root), [])

    def test_buildsrc_is_skipped(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", "dependencies { implementation 'g:a:1.0' }\n")
            write(root, "buildSrc/build.gradle", "dependencies { implementation 'g:b:2.0' }\n")
            found = gr.find_build_files(root)
            self.assertEqual(found, [os.path.join(root, "build.gradle")])

    def test_mapping_is_repo_relative_and_poms_are_written(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "build.gradle", "dependencies { implementation 'com.google.guava:guava:30.1-jre' }\n")
            write(root, "app/build.gradle.kts",
                  'dependencies { implementation("org.apache.commons:commons-text:1.9") }\n')
            result = subprocess.run([sys.executable, "-I", RESOLVER, root, out],
                                    capture_output=True, text=True, check=True)
            mapping = json.loads(result.stdout)
            build_files = sorted(entry["buildFile"] for entry in mapping)
            self.assertEqual(build_files, ["app/build.gradle.kts", "build.gradle"])
            for entry in mapping:
                self.assertTrue(entry["syntheticPath"].startswith(out))
                self.assertTrue(os.path.isfile(entry["syntheticPath"]))
                with open(entry["syntheticPath"], encoding="utf-8") as handle:
                    pom = handle.read()
                self.assertIn("<modelVersion>4.0.0</modelVersion>", pom)
                self.assertIn("<dependency>", pom)

    def test_no_concrete_deps_yields_no_pom(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "build.gradle", "dependencies { implementation 'g:a:1.2.+' }\n")
            result = subprocess.run([sys.executable, "-I", RESOLVER, root, out],
                                    capture_output=True, text=True, check=True)
            self.assertEqual(json.loads(result.stdout), [])

    def test_isolated_mode_runs_against_a_tree_with_a_planted_json_module(self):
        # D-004: reading the scanned tree must never import code from it. Plant a hostile json.py in the
        # checkout; `python3 -I` keeps the tree off sys.path, so the resolver still runs and uses stdlib json.
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "json.py", "raise SystemExit('hostile module executed')\n")
            write(root, "tomllib.py", "raise SystemExit('hostile module executed')\n")
            write(root, "build.gradle", "dependencies { implementation 'com.google.guava:guava:30.1-jre' }\n")
            result = subprocess.run([sys.executable, "-I", RESOLVER, root, out],
                                    capture_output=True, text=True, cwd=root, check=True)
            mapping = json.loads(result.stdout)
        self.assertEqual([e["buildFile"] for e in mapping], ["build.gradle"])


class SafetyTests(unittest.TestCase):
    def test_coordinate_with_xml_or_shell_metacharacters_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation 'g:a</version><!--:1.0'
                    implementation 'ok:lib:1.0'
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertEqual(coords, [("ok", "lib", "1.0")])

    def test_malformed_build_file_does_not_raise(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", "dependencies { implementation 'g:a:1.0'\n/* unterminated")
            # Must not raise; returns whatever it could parse (possibly empty).
            resolve(root, "build.gradle")


if __name__ == "__main__":
    unittest.main(verbosity=2)
