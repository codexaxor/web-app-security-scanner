#!/usr/bin/env python3
"""Unit tests for the static Gradle resolver (gradle_resolver.py), the D-039 gate.

Offline, stdlib ``unittest`` only. Wired into ``./gradlew build`` via the ``gradleResolverTest`` task
(app/build.gradle.kts), so a resolver regression fails the DEFAULT build. The behavioural end-to-end proof
(real transitive CVEs via OSV, incl. BOM-managed versions) is the live-osv ``ScaLiveScanIT``.

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


def _resolve(root, rel):
    libraries, bundles = gr.load_version_catalog(root)
    return gr.resolve_build_file(root, os.path.join(root, rel), libraries, bundles)


def resolve(root, rel):
    """Regular dependencies as (group, artifact, version_or_None); drops the injected <type>pom> BOM trigger."""
    deps, _, _ = _resolve(root, rel)
    return [(g, a, v) for (g, a, v, is_pom) in deps if not is_pom]


def deps_full(root, rel):
    """All synthetic-pom dependencies as (group, artifact, version_or_None, is_pom)."""
    return _resolve(root, rel)[0]


def boms(root, rel):
    return _resolve(root, rel)[1]


def details(root, rel):
    return _resolve(root, rel)[2]


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
                dependencies { implementation("com.google.guava:guava:30.1-jre") }
            """)
            coords = resolve(root, "build.gradle.kts")
        self.assertEqual(coords, [("com.google.guava", "guava", "30.1-jre")])

    def test_map_notation(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies { implementation group: 'commons-io', name: 'commons-io', version: '2.6' }
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


class ConfigSelectionTests(unittest.TestCase):
    """Over-include EVERY non-buildSrc configuration (production and test); keep wins; dev/test exclusion is M7."""

    def test_package_in_both_production_and_test_configs_is_kept(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation 'com.google.guava:guava:30.1-jre'
                    testImplementation 'com.google.guava:guava:30.1-jre'
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertEqual(coords, [("com.google.guava", "guava", "30.1-jre")])

    def test_test_only_package_is_included_over_included(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation 'com.google.guava:guava:30.1-jre'
                    testImplementation 'org.mockito:mockito-core:5.0.0'
                    integrationTestRuntimeOnly 'io.rest-assured:rest-assured:5.3.0'
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertIn(("com.google.guava", "guava", "30.1-jre"), coords)
        self.assertIn(("org.mockito", "mockito-core", "5.0.0"), coords)
        self.assertIn(("io.rest-assured", "rest-assured", "5.3.0"), coords)

    def test_non_config_keywords_and_internal_forms_ignored(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                buildscript { dependencies { classpath 'com.example:plugin:1.0' } }
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

    def test_ext_block_and_set_and_extra_interpolation(self):
        # D-041 review #2: ext{} set('prop','v'), Kotlin extra[...] and bare ext-block assignments all resolve.
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                ext {
                    set('springBootAdminVersion', '2.6.5')
                    camelVersion = '3.14.0'
                }
                dependencies {
                    implementation "de.codecentric:spring-boot-admin-starter-client:${springBootAdminVersion}"
                    implementation "org.apache.camel:camel-core:$camelVersion"
                }
            """)
            coords = resolve(root, "build.gradle")
        self.assertIn(("de.codecentric", "spring-boot-admin-starter-client", "2.6.5"), coords)
        self.assertIn(("org.apache.camel", "camel-core", "3.14.0"), coords)


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
            write(root, "build.gradle.kts", "dependencies { implementation(libs.bundles.jackson) }\n")
            coords = resolve(root, "build.gradle.kts")
        self.assertEqual(coords, [("com.fasterxml.jackson.core", "jackson-databind", "2.15.0")])


class BomImportTests(unittest.TestCase):
    """D-041: BOM-managed versions resolve via a <dependencyManagement> import; version-less deps are emitted
    version-less under the BOM (no warning)."""

    def test_boot_plugin_version_becomes_dependency_management_import(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                plugins { id 'org.springframework.boot' version '2.6.5' }
                dependencies { implementation 'org.springframework.boot:spring-boot-starter-web' }
            """)
            deps, bs, warns = _resolve(root, "build.gradle")
        self.assertIn(("org.springframework.boot", "spring-boot-dependencies", "2.6.5"), bs)
        # The version-less starter is emitted version-less (managed by the BOM), with no warning.
        self.assertIn(("org.springframework.boot", "spring-boot-starter-web", None, False), deps)
        self.assertEqual(warns, [])
        # Every dependency is BOM-managed, so the BOM is also injected as a <type>pom> trigger for OSV.
        self.assertIn(("org.springframework.boot", "spring-boot-dependencies", "2.6.5", True), deps)

    def test_boot_plugin_kotlin_and_legacy_classpath(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle.kts",
                  'plugins { id("org.springframework.boot") version "3.1.2" }\n')
            self.assertIn(("org.springframework.boot", "spring-boot-dependencies", "3.1.2"),
                          boms(root, "build.gradle.kts"))
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                buildscript { dependencies {
                    classpath 'org.springframework.boot:spring-boot-gradle-plugin:2.7.0'
                } }
            """)
            self.assertIn(("org.springframework.boot", "spring-boot-dependencies", "2.7.0"),
                          boms(root, "build.gradle"))

    def test_maven_bom_with_ext_property(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                ext { set('springCloudVersion', '2021.0.4') }
                dependencyManagement { imports {
                    mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
                } }
                dependencies { implementation 'org.springframework.cloud:spring-cloud-starter-openfeign' }
            """)
            deps, bs, warns = _resolve(root, "build.gradle")
        self.assertIn(("org.springframework.cloud", "spring-cloud-dependencies", "2021.0.4"), bs)
        self.assertIn(("org.springframework.cloud", "spring-cloud-starter-openfeign", None, False), deps)
        self.assertEqual(warns, [])

    def test_platform_and_enforced_platform_become_bom_imports(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation platform('org.springframework.boot:spring-boot-dependencies:3.2.0')
                    implementation enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.15.0")
                }
            """)
            bs = boms(root, "build.gradle")
        self.assertIn(("org.springframework.boot", "spring-boot-dependencies", "3.2.0"), bs)
        self.assertIn(("com.fasterxml.jackson", "jackson-bom", "2.15.0"), bs)

    def test_no_version_dep_under_bom_emits_version_less_without_warning(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                plugins { id 'org.springframework.boot' version '2.6.5' }
                dependencies {
                    implementation 'org.apache.commons:commons-text:1.12.0'
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                }
            """)
            deps, _, warns = _resolve(root, "build.gradle")
        self.assertIn(("org.apache.commons", "commons-text", "1.12.0", False), deps)
        self.assertIn(("org.springframework.boot", "spring-boot-starter-web", None, False), deps)
        self.assertEqual(warns, [])
        # A concrete dep (commons-text) is present, so NO pom-type trigger is injected.
        self.assertFalse(any(is_pom for _, _, _, is_pom in deps))

    def test_explicit_non_concrete_version_still_warns_even_with_a_bom(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                plugins { id 'org.springframework.boot' version '2.6.5' }
                dependencies { implementation 'org.example:thing:1.2.+' }
            """)
            deps, _, warns = _resolve(root, "build.gradle")
        self.assertNotIn(("org.example", "thing", None, False), deps)  # NOT emitted version-less
        self.assertTrue(any("non-concrete version" in w for w in warns))


class WarningTests(unittest.TestCase):
    """Item #2: every skipped construct / unresolved version is a VISIBLE coverage gap (D-030), never silent."""

    def test_one_warning_per_non_concrete_version(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation 'g:dynamic:1.2.+'
                    implementation 'g:range:[1.0,2.0)'
                    implementation 'g:unresolved:$missingVersion'
                    implementation 'g:concrete:1.2.3'
                }
            """)
            warnings = details(root, "build.gradle")
        self.assertEqual(len(warnings), 3)
        self.assertTrue(all("non-concrete version" in w for w in warnings))

    def test_warning_for_version_less_dep_with_no_bom(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies { implementation 'org.springframework.boot:spring-boot-starter-web' }
            """)
            warnings = details(root, "build.gradle")
        self.assertEqual(len(warnings), 1)
        self.assertIn("no version", warnings[0])
        self.assertIn("no BOM", warnings[0])

    def test_warning_for_unresolved_catalog_reference(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "gradle/libs.versions.toml", "[libraries]\n")
            write(root, "build.gradle.kts", """
                dependencies {
                    implementation(libs.not.in.catalog)
                    implementation(libs.bundles.missing)
                }
            """)
            warnings = details(root, "build.gradle.kts")
        self.assertEqual(len(warnings), 2)
        self.assertTrue(any("libs.not.in.catalog" in w for w in warnings))
        self.assertTrue(any("libs.bundles.missing" in w for w in warnings))

    def test_warning_for_apply_from_and_resolution_strategy(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                apply from: 'versions.gradle'
                configurations.all { resolutionStrategy { force 'g:a:1.0' } }
                dependencies { implementation 'com.google.guava:guava:30.1-jre' }
            """)
            warnings = details(root, "build.gradle")
        self.assertTrue(any("apply from" in w for w in warnings))
        self.assertTrue(any("resolutionStrategy" in w for w in warnings))

    def test_buildsrc_build_file_is_warned_as_unresolved(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "build.gradle", "dependencies { implementation 'com.google.guava:guava:30.1-jre' }\n")
            write(root, "buildSrc/build.gradle", "dependencies { implementation 'g:b:2.0' }\n")
            result = gr.resolve(root, out)
        self.assertEqual([p["buildFile"] for p in result["poms"]], ["build.gradle"])
        buildsrc_warnings = [w for w in result["warnings"] if "buildSrc" in w["message"]]
        self.assertEqual(len(buildsrc_warnings), 1)
        self.assertEqual(buildsrc_warnings[0]["code"], gr.WARN_CODE)
        self.assertEqual(buildsrc_warnings[0]["filePath"], "buildSrc/build.gradle")

    def test_write_failure_emits_a_warning_not_a_silent_empty_map(self):
        # D-041 review #3: if the synthetic pom / out-dir cannot be written, emit a scan warning.
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as tmp:
            write(root, "build.gradle", "dependencies { implementation 'com.google.guava:guava:30.1-jre' }\n")
            out_as_file = os.path.join(tmp, "not-a-dir")
            with open(out_as_file, "w", encoding="utf-8") as handle:
                handle.write("x")  # out-dir is actually a file -> makedirs under it raises OSError
            result = gr.resolve(root, out_as_file)
        self.assertEqual(result["poms"], [])
        self.assertTrue(any("could not write" in w["message"] for w in result["warnings"]))
        self.assertTrue(all(w["code"] == gr.WARN_CODE for w in result["warnings"]))


class WalkAndOutputTests(unittest.TestCase):
    def test_build_file_with_lockfile_is_skipped(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", "dependencies { implementation 'g:a:1.0' }\n")
            write(root, "gradle.lockfile", "# locked\n")
            eligible, _ = gr.find_build_files(root)
            self.assertEqual(eligible, [])

    def test_mapping_is_repo_relative_and_poms_are_written(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "build.gradle", "dependencies { implementation 'com.google.guava:guava:30.1-jre' }\n")
            write(root, "app/build.gradle.kts",
                  'dependencies { implementation("org.apache.commons:commons-text:1.9") }\n')
            result = subprocess.run([sys.executable, "-I", RESOLVER, root, out],
                                    capture_output=True, text=True, check=True)
            poms = json.loads(result.stdout)["poms"]
            build_files = sorted(entry["buildFile"] for entry in poms)
            self.assertEqual(build_files, ["app/build.gradle.kts", "build.gradle"])
            for entry in poms:
                self.assertTrue(entry["syntheticPath"].startswith(out))
                self.assertTrue(os.path.isfile(entry["syntheticPath"]))
                with open(entry["syntheticPath"], encoding="utf-8") as handle:
                    pom = handle.read()
                self.assertIn("<modelVersion>4.0.0</modelVersion>", pom)
                self.assertIn("<dependency>", pom)

    def test_pure_bom_pom_has_dependency_management_import_and_trigger(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "build.gradle", """
                plugins { id 'org.springframework.boot' version '2.6.5' }
                ext { set('springCloudVersion', '2021.0.4') }
                dependencyManagement { imports {
                    mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
                } }
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                    implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'
                }
            """)
            subprocess.run([sys.executable, "-I", RESOLVER, root, out], capture_output=True, check=True)
            with open(os.path.join(out, "0", "pom.xml"), encoding="utf-8") as handle:
                pom = handle.read()
        self.assertIn("<dependencyManagement>", pom)
        self.assertIn("<scope>import</scope>", pom)
        self.assertIn("spring-boot-dependencies", pom)
        self.assertIn("spring-cloud-dependencies", pom)
        # version-less managed deps present with NO <version> of their own
        self.assertIn("spring-boot-starter-web", pom)
        self.assertIn("spring-cloud-starter-openfeign", pom)

    def test_no_concrete_deps_yields_no_pom(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "build.gradle", "dependencies { implementation 'g:a:1.2.+' }\n")
            result = subprocess.run([sys.executable, "-I", RESOLVER, root, out],
                                    capture_output=True, text=True, check=True)
            parsed = json.loads(result.stdout)
        self.assertEqual(parsed["poms"], [])
        self.assertTrue(any("non-concrete version" in w["message"] for w in parsed["warnings"]))

    def test_isolated_mode_runs_against_a_tree_with_a_planted_json_module(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "json.py", "raise SystemExit('hostile module executed')\n")
            write(root, "tomllib.py", "raise SystemExit('hostile module executed')\n")
            write(root, "build.gradle", "dependencies { implementation 'com.google.guava:guava:30.1-jre' }\n")
            result = subprocess.run([sys.executable, "-I", RESOLVER, root, out],
                                    capture_output=True, text=True, cwd=root, check=True)
            poms = json.loads(result.stdout)["poms"]
            self.assertEqual([p["buildFile"] for p in poms], ["build.gradle"])


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
            resolve(root, "build.gradle")  # must not raise


if __name__ == "__main__":
    unittest.main(verbosity=2)
