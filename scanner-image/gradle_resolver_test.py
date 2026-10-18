#!/usr/bin/env python3
"""Unit tests for the static Gradle resolver (gradle_resolver.py), the D-039/D-042 gate.

Offline, stdlib ``unittest`` only. BOM POMs are supplied through an in-memory ``DictFetcher`` (no network).
Wired into ``./gradlew build`` via the ``gradleResolverTest`` task, so a resolver regression fails the DEFAULT
build. The behavioural end-to-end proof (real Maven-Central BOM fetch + transitive CVEs via OSV) is the
live-osv ``ScaLiveScanIT``.

Run directly: ``python3 gradle_resolver_test.py`` (NOT ``-I`` -- the harness imports the resolver module).
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gradle_resolver as gr  # noqa: E402

RESOLVER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gradle_resolver.py")


def write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return path


def make_pom(group, artifact, version, parent=None, properties=None, managed=None):
    """A minimal Maven POM string. ``parent`` = (g,a,v); ``properties`` = {name: value}; ``managed`` = list of
    (group, artifact, version, scope, type)."""
    lines = ['<project xmlns="http://maven.apache.org/POM/4.0.0">', "  <modelVersion>4.0.0</modelVersion>"]
    if parent:
        lines += ["  <parent>", "    <groupId>%s</groupId>" % parent[0],
                  "    <artifactId>%s</artifactId>" % parent[1], "    <version>%s</version>" % parent[2],
                  "  </parent>"]
    lines += ["  <groupId>%s</groupId>" % group, "  <artifactId>%s</artifactId>" % artifact,
              "  <version>%s</version>" % version]
    if properties:
        lines.append("  <properties>")
        for k, v in properties.items():
            lines.append("    <%s>%s</%s>" % (k, v, k))
        lines.append("  </properties>")
    if managed:
        lines += ["  <dependencyManagement>", "    <dependencies>"]
        for (mg, ma, mv, scope, mtype) in managed:
            lines.append("      <dependency>")
            lines.append("        <groupId>%s</groupId>" % mg)
            lines.append("        <artifactId>%s</artifactId>" % ma)
            lines.append("        <version>%s</version>" % mv)
            if scope:
                lines.append("        <scope>%s</scope>" % scope)
            if mtype:
                lines.append("        <type>%s</type>" % mtype)
            lines.append("      </dependency>")
        lines += ["    </dependencies>", "  </dependencyManagement>"]
    lines.append("</project>")
    return "\n".join(lines)


class DictFetcher:
    """In-memory stand-in for MavenFetcher: serves POMs from a {(group, artifact, version): pom_text} map."""

    def __init__(self, poms=None):
        self.poms = poms or {}
        self.requested = []

    def fetch(self, group, artifact, version):
        self.requested.append((group, artifact, version))
        return self.poms.get((group, artifact, version))


def _resolve(root, rel, fetcher=None):
    libraries, bundles = gr.load_version_catalog(root)
    return gr.resolve_build_file(root, os.path.join(root, rel), libraries, bundles, fetcher or DictFetcher())


def resolve(root, rel, fetcher=None):
    """Regular dependencies as (group, artifact, version) -- every one concrete (D-042)."""
    return _resolve(root, rel, fetcher)[0]


def boms(root, rel, fetcher=None):
    return _resolve(root, rel, fetcher)[1]


def details(root, rel, fetcher=None):
    """Warning detail strings (code stripped)."""
    return [d for _, d in _resolve(root, rel, fetcher)[2]]


def codes(root, rel, fetcher=None):
    return [c for c, _ in _resolve(root, rel, fetcher)[2]]


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
            write(root, "build.gradle.kts", 'dependencies { implementation("com.google.guava:guava:30.1-jre") }\n')
            self.assertEqual(resolve(root, "build.gradle.kts"), [("com.google.guava", "guava", "30.1-jre")])

    def test_map_notation(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle",
                  "dependencies { implementation group: 'commons-io', name: 'commons-io', version: '2.6' }\n")
            self.assertEqual(resolve(root, "build.gradle"), [("commons-io", "commons-io", "2.6")])

    def test_dependency_with_trailing_closure(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation('org.apache.commons:commons-text:1.9') {
                        exclude group: 'org.apache.commons', module: 'commons-lang3'
                    }
                }
            """)
            self.assertEqual(resolve(root, "build.gradle"), [("org.apache.commons", "commons-text", "1.9")])


class ConfigSelectionTests(unittest.TestCase):
    def test_package_in_both_production_and_test_configs_is_kept(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation 'com.google.guava:guava:30.1-jre'
                    testImplementation 'com.google.guava:guava:30.1-jre'
                }
            """)
            self.assertEqual(resolve(root, "build.gradle"), [("com.google.guava", "guava", "30.1-jre")])

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
            self.assertEqual(resolve(root, "build.gradle"), [("com.google.guava", "guava", "30.1-jre")])


class VersionResolutionTests(unittest.TestCase):
    def test_non_concrete_versions_skipped_not_guessed(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation 'g:dynamic:1.2.+'
                    implementation 'g:range:[1.0,2.0)'
                    implementation 'g:unresolved:$missingVersion'
                    implementation 'g:concrete:1.2.3'
                }
            """)
            self.assertEqual(resolve(root, "build.gradle"), [("g", "concrete", "1.2.3")])

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

    def test_ext_block_and_set_and_extra_interpolation(self):
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
        [libraries]
        guava = { module = "com.google.guava:guava", version.ref = "guava" }
        commons-text = { group = "org.apache.commons", name = "commons-text", version = "1.9" }
        [bundles]
        web = ["guava", "commons-text"]
    """

    def test_catalog_accessors_and_bundle(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "gradle/libs.versions.toml", self.CATALOG)
            write(root, "build.gradle.kts", """
                dependencies {
                    implementation(libs.guava)
                    implementation(libs.bundles.web)
                }
            """)
            coords = resolve(root, "build.gradle.kts")
        self.assertIn(("com.google.guava", "guava", "30.1-jre"), coords)
        self.assertIn(("org.apache.commons", "commons-text", "1.9"), coords)


class BomExpansionTests(unittest.TestCase):
    """D-042: the resolver fetches BOM POMs and writes a CONCRETE <version> on every dependency."""

    def test_boot_plugin_version_resolves_managed_dependency(self):
        fetcher = DictFetcher({
            ("org.springframework.boot", "spring-boot-dependencies", "2.6.5"): make_pom(
                "org.springframework.boot", "spring-boot-dependencies", "2.6.5",
                managed=[("org.springframework", "spring-web", "5.3.16", "", "")]),
        })
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                plugins { id 'org.springframework.boot' version '2.6.5' }
                dependencies { implementation 'org.springframework:spring-web' }
            """)
            deps, bs, warns = _resolve(root, "build.gradle", fetcher)
        self.assertIn(("org.springframework", "spring-web", "5.3.16"), deps)  # concrete version from the BOM
        self.assertIn(("org.springframework.boot", "spring-boot-dependencies", "2.6.5"), bs)
        self.assertEqual(warns, [])

    def test_maven_bom_with_properties_parent_chain_and_nested_import(self):
        fetcher = DictFetcher({
            ("com.acme", "acme-bom", "1.0"): make_pom(
                "com.acme", "acme-bom", "1.0", parent=("com.acme", "acme-parent", "1.0"),
                properties={"thing.version": "9.9"},
                managed=[("com.acme", "thing", "${thing.version}", "", ""),
                         ("com.acme", "sub-bom", "2.0", "import", "pom")]),
            ("com.acme", "acme-parent", "1.0"): make_pom(
                "com.acme", "acme-parent", "1.0", properties={"legacy.version": "1.2.3"},
                managed=[("com.acme", "legacy", "${legacy.version}", "", "")]),
            ("com.acme", "sub-bom", "2.0"): make_pom(
                "com.acme", "sub-bom", "2.0", managed=[("com.acme", "widget", "4.5.6", "", "")]),
        })
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                ext { set('acmeVersion', '1.0') }
                dependencyManagement { imports { mavenBom "com.acme:acme-bom:${acmeVersion}" } }
                dependencies {
                    implementation 'com.acme:thing'
                    implementation 'com.acme:legacy'
                    implementation 'com.acme:widget'
                    implementation 'com.acme:unmanaged'
                }
            """)
            deps, _, warns = _resolve(root, "build.gradle", fetcher)
        self.assertIn(("com.acme", "thing", "9.9"), deps)      # ${prop} in the BOM resolved
        self.assertIn(("com.acme", "legacy", "1.2.3"), deps)   # from the parent BOM
        self.assertIn(("com.acme", "widget", "4.5.6"), deps)   # from the nested <import> BOM
        self.assertNotIn("com.acme:unmanaged", [g + ":" + a for (g, a, _) in deps])
        self.assertTrue(any(code == gr.WARN_CODE and "unmanaged" in d for code, d in warns))

    def test_dependency_absent_from_every_bom_warns_and_is_not_dropped_silently(self):
        fetcher = DictFetcher({
            ("org.springframework.boot", "spring-boot-dependencies", "2.6.5"): make_pom(
                "org.springframework.boot", "spring-boot-dependencies", "2.6.5", managed=[]),
        })
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                plugins { id 'org.springframework.boot' version '2.6.5' }
                dependencies { implementation 'com.example:not-in-bom' }
            """)
            deps, _, warns = _resolve(root, "build.gradle", fetcher)
        self.assertEqual(deps, [])
        self.assertTrue(any(code == gr.WARN_CODE and "no imported BOM manages it" in d for code, d in warns))

    def test_bom_fetch_failure_warns_and_scan_continues(self):
        fetcher = DictFetcher({})  # every fetch returns None
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                plugins { id 'org.springframework.boot' version '2.6.5' }
                dependencies { implementation 'org.springframework:spring-web' }
            """)
            deps, _, warns = _resolve(root, "build.gradle", fetcher)  # must not raise
        self.assertTrue(any(code == gr.WARN_FETCH for code, _ in warns))
        self.assertEqual(deps, [])

    def test_explicit_non_concrete_version_still_warns_even_with_a_bom(self):
        fetcher = DictFetcher({
            ("org.springframework.boot", "spring-boot-dependencies", "2.6.5"): make_pom(
                "org.springframework.boot", "spring-boot-dependencies", "2.6.5", managed=[]),
        })
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                plugins { id 'org.springframework.boot' version '2.6.5' }
                dependencies { implementation 'org.example:thing:1.2.+' }
            """)
            deps, _, warns = _resolve(root, "build.gradle", fetcher)
        self.assertEqual(deps, [])
        self.assertTrue(any("non-concrete version" in d for _, d in warns))

    def test_platform_becomes_a_bom_import(self):
        fetcher = DictFetcher({
            ("com.acme", "acme-bom", "3.0"): make_pom("com.acme", "acme-bom", "3.0",
                                                      managed=[("com.acme", "lib", "7.7", "", "")]),
        })
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation platform('com.acme:acme-bom:3.0')
                    implementation 'com.acme:lib'
                }
            """)
            deps, bs, _ = _resolve(root, "build.gradle", fetcher)
        self.assertIn(("com.acme", "acme-bom", "3.0"), bs)
        self.assertIn(("com.acme", "lib", "7.7"), deps)


class NetworkTargetTests(unittest.TestCase):
    """D-042: the resolver fetches .pom files from ONE host only, over HTTPS, with TLS verification on."""

    def test_default_base_is_maven_central_over_https(self):
        base = gr.maven_base_url()
        self.assertEqual(base, gr.DEFAULT_MAVEN_BASE)
        self.assertEqual(urlsplit(base).scheme, "https")
        self.assertEqual(urlsplit(base).hostname, "repo.maven.apache.org")

    def test_pom_url_is_under_the_allowed_host(self):
        url = gr.pom_url(gr.DEFAULT_MAVEN_BASE, "org.springframework.boot", "spring-boot-dependencies", "2.6.5")
        self.assertEqual(url, "https://repo.maven.apache.org/maven2/org/springframework/boot/"
                              "spring-boot-dependencies/2.6.5/spring-boot-dependencies-2.6.5.pom")
        self.assertEqual(urlsplit(url).hostname, "repo.maven.apache.org")

    def test_fetcher_allowed_host_and_mirror_env(self):
        self.assertEqual(gr.MavenFetcher(gr.DEFAULT_MAVEN_BASE).allowed_host, "repo.maven.apache.org")
        self.assertEqual(gr.MavenFetcher("https://mirror.internal/m2").allowed_host, "mirror.internal")

    def test_fetcher_honours_total_cap(self):
        f = gr.MavenFetcher(gr.DEFAULT_MAVEN_BASE, total_cap=0)
        self.assertIsNone(f.fetch("g", "a", "1.0"))  # cap 0 -> never performs a network call


class PrivateRegistryTests(unittest.TestCase):
    """D-032 (slice 3): detect private feeds and WARN (host only); never fetch them, never leak credentials."""

    FIXTURE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "fixtures", "sca-private-registry")

    def test_private_maven_repo_warns_host_only_public_repos_do_not(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                repositories {
                    mavenCentral()
                    google()
                    gradlePluginPortal()
                    maven { url 'https://repo.maven.apache.org/maven2' }
                    maven { url 'https://nexus.corp.example/repository/maven-releases/' }
                }
                dependencies { implementation 'com.google.guava:guava:30.1-jre' }
            """)
            msgs = [d for c, d in _resolve(root, "build.gradle")[2] if c == gr.WARN_PRIVATE_REGISTRY]
        self.assertEqual(len(msgs), 1)  # only the non-public host; mavenCentral()/google()/public url do not warn
        self.assertIn("nexus.corp.example", msgs[0])
        self.assertIn("(D-032)", msgs[0])

    def test_kotlin_and_uri_and_shorthand_forms(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle.kts", """
                repositories {
                    maven { url = uri("https://a.corp.example/repo") }
                    maven("https://b.corp.example/repo")
                    maven { setUrl("https://c.corp.example/repo") }
                }
            """)
            hosts = sorted(d.split("(")[1].split(")")[0]
                           for c, d in _resolve(root, "build.gradle.kts")[2] if c == gr.WARN_PRIVATE_REGISTRY)
        self.assertEqual(hosts, ["a.corp.example", "b.corp.example", "c.corp.example"])

    def test_credential_bearing_url_surfaces_host_only(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle",
                  "repositories { maven { url 'https://svcuser:s3cr3tT0ken@artifactory.corp.example/libs' } }\n")
            msgs = [d for c, d in _resolve(root, "build.gradle")[2] if c == gr.WARN_PRIVATE_REGISTRY]
        self.assertEqual(len(msgs), 1)
        self.assertIn("artifactory.corp.example", msgs[0])
        for secret in ("svcuser", "s3cr3tT0ken", "@"):
            self.assertNotIn(secret, msgs[0])

    def test_unresolved_url_still_warns_without_leaking_the_expression(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", "repositories { maven { url \"${nexusUrl}\" } }\n")
            msgs = [d for c, d in _resolve(root, "build.gradle")[2] if c == gr.WARN_PRIVATE_REGISTRY]
        self.assertEqual(len(msgs), 1)
        self.assertIn("could not be resolved", msgs[0])

    def test_private_registry_detection_makes_no_network_request(self):
        # The private host must NEVER be fetched (SSRF-shaped risk). A build file with a private repo and NO BOM
        # performs zero fetches; the fetcher's request log stays empty.
        fetcher = DictFetcher()
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                repositories { maven { url 'https://nexus.corp.example/repo' } }
                dependencies { implementation 'com.google.guava:guava:30.1-jre' }
            """)
            _, _, warns = gr.resolve_build_file(root, os.path.join(root, "build.gradle"), {}, {}, fetcher)
        self.assertEqual(fetcher.requested, [])  # no fetch to the private host (or anywhere)
        self.assertTrue(any(c == gr.WARN_PRIVATE_REGISTRY for c, _ in warns))

    def test_settings_gradle_repositories_are_scanned(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "settings.gradle", """
                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                        maven { url 'https://settings-nexus.corp.example/repo' }
                    }
                }
            """)
            result = gr.resolve(root, out, fetcher=DictFetcher())
        msgs = [w for w in result["warnings"] if w["code"] == gr.WARN_PRIVATE_REGISTRY]
        self.assertEqual(len(msgs), 1)
        self.assertEqual(msgs[0]["filePath"], "settings.gradle")
        self.assertIn("settings-nexus.corp.example", msgs[0]["message"])

    def test_nuget_private_source_warns_host_only_credentials_never_read(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "nuget.config", """<?xml version="1.0"?>
                <configuration>
                  <packageSources>
                    <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
                    <add key="corp" value="https://nuget.corp.example/v3/index.json" />
                  </packageSources>
                  <packageSourceCredentials>
                    <corp><add key="ClearTextPassword" value="p@ssw0rd-TOPSECRET" /></corp>
                  </packageSourceCredentials>
                </configuration>""")
            result = gr.resolve(root, out, fetcher=DictFetcher())
        msgs = [w for w in result["warnings"] if w["code"] == gr.WARN_PRIVATE_REGISTRY]
        self.assertEqual(len(msgs), 1)  # api.nuget.org is public, corp is private
        self.assertEqual(msgs[0]["filePath"], "nuget.config")
        self.assertIn("nuget.corp.example", msgs[0]["message"])
        self.assertNotIn("TOPSECRET", json.dumps(result))

    def test_nuget_org_only_config_does_not_warn(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "nuget.config", """<?xml version="1.0"?>
                <configuration><packageSources>
                  <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
                </packageSources></configuration>""")
            result = gr.resolve(root, out, fetcher=DictFetcher())
        self.assertEqual([w for w in result["warnings"] if w["code"] == gr.WARN_PRIVATE_REGISTRY], [])

    def test_fixture_output_contains_hosts_but_no_credentials_anywhere(self):
        # Hard hygiene check over the resolver's entire JSON output for the committed fixture.
        with tempfile.TemporaryDirectory() as out:
            result = gr.resolve(self.FIXTURE, out, fetcher=DictFetcher())
        dumped = json.dumps(result)
        for host in ("nexus.corp.example", "artifactory.corp.example", "nuget.corp.example"):
            self.assertIn(host, dumped)
        for secret in ("s3cr3tT0ken", "svcuser", "svc-account", "p@ssw0rd-TOPSECRET", "ClearTextPassword", "@"):
            self.assertNotIn(secret, dumped)


class WarningTests(unittest.TestCase):
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
            ds = details(root, "build.gradle")
        self.assertEqual(len([d for d in ds if "non-concrete version" in d]), 3)

    def test_warning_for_version_less_dep_with_no_bom(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle",
                  "dependencies { implementation 'org.springframework.boot:spring-boot-starter-web' }\n")
            ds = details(root, "build.gradle")
        self.assertEqual(len(ds), 1)
        self.assertIn("no version", ds[0])
        self.assertIn("BOM", ds[0])

    def test_warning_for_unresolved_catalog_reference(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "gradle/libs.versions.toml", "[libraries]\n")
            write(root, "build.gradle.kts", """
                dependencies {
                    implementation(libs.not.in.catalog)
                    implementation(libs.bundles.missing)
                }
            """)
            ds = details(root, "build.gradle.kts")
        self.assertEqual(len(ds), 2)
        self.assertTrue(any("libs.not.in.catalog" in d for d in ds))
        self.assertTrue(any("libs.bundles.missing" in d for d in ds))

    def test_warning_for_apply_from_and_resolution_strategy(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                apply from: 'versions.gradle'
                configurations.all { resolutionStrategy { force 'g:a:1.0' } }
                dependencies { implementation 'com.google.guava:guava:30.1-jre' }
            """)
            ds = details(root, "build.gradle")
        self.assertTrue(any("apply from" in d for d in ds))
        self.assertTrue(any("resolutionStrategy" in d for d in ds))

    def test_buildsrc_build_file_is_warned(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "build.gradle", "dependencies { implementation 'com.google.guava:guava:30.1-jre' }\n")
            write(root, "buildSrc/build.gradle", "dependencies { implementation 'g:b:2.0' }\n")
            result = gr.resolve(root, out, fetcher=DictFetcher())
        self.assertEqual([p["buildFile"] for p in result["poms"]], ["build.gradle"])
        bs = [w for w in result["warnings"] if "buildSrc" in w["message"]]
        self.assertEqual(len(bs), 1)
        self.assertEqual(bs[0]["code"], gr.WARN_CODE)
        self.assertEqual(bs[0]["filePath"], "buildSrc/build.gradle")

    def test_write_failure_emits_a_warning_not_a_silent_empty_map(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as tmp:
            write(root, "build.gradle", "dependencies { implementation 'com.google.guava:guava:30.1-jre' }\n")
            out_as_file = os.path.join(tmp, "not-a-dir")
            with open(out_as_file, "w", encoding="utf-8") as handle:
                handle.write("x")
            result = gr.resolve(root, out_as_file, fetcher=DictFetcher())
        self.assertEqual(result["poms"], [])
        self.assertTrue(any("could not write" in w["message"] for w in result["warnings"]))


class WalkAndOutputTests(unittest.TestCase):
    def test_build_file_with_lockfile_is_skipped(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", "dependencies { implementation 'g:a:1.0' }\n")
            write(root, "gradle.lockfile", "# locked\n")
            self.assertEqual(gr.find_build_files(root)[0], [])

    def test_synthetic_pom_has_concrete_versions_and_dependency_management(self):
        fetcher = DictFetcher({
            ("org.springframework.boot", "spring-boot-dependencies", "2.6.5"): make_pom(
                "org.springframework.boot", "spring-boot-dependencies", "2.6.5",
                managed=[("org.springframework.boot", "spring-boot-starter-web", "2.6.5", "", "")]),
        })
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "build.gradle", """
                plugins { id 'org.springframework.boot' version '2.6.5' }
                dependencies { implementation 'org.springframework.boot:spring-boot-starter-web' }
            """)
            result = gr.resolve(root, out, fetcher=fetcher)
            self.assertEqual(len(result["poms"]), 1)
            with open(result["poms"][0]["syntheticPath"], encoding="utf-8") as handle:
                pom = handle.read()
        self.assertIn("<dependencyManagement>", pom)
        self.assertIn("<scope>import</scope>", pom)
        # the formerly version-less starter now carries a concrete <version>
        self.assertRegex(pom, r"spring-boot-starter-web</artifactId>\s*<version>2\.6\.5</version>")

    def test_mapping_is_repo_relative(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "build.gradle", "dependencies { implementation 'com.google.guava:guava:30.1-jre' }\n")
            write(root, "app/build.gradle.kts",
                  'dependencies { implementation("org.apache.commons:commons-text:1.9") }\n')
            result = gr.resolve(root, out, fetcher=DictFetcher())
            self.assertEqual(sorted(p["buildFile"] for p in result["poms"]),
                             ["app/build.gradle.kts", "build.gradle"])
            for entry in result["poms"]:
                self.assertTrue(os.path.isfile(entry["syntheticPath"]))

    def test_isolated_mode_runs_against_a_tree_with_a_planted_json_module(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            write(root, "json.py", "raise SystemExit('hostile module executed')\n")
            write(root, "tomllib.py", "raise SystemExit('hostile module executed')\n")
            write(root, "build.gradle", "dependencies { implementation 'com.google.guava:guava:30.1-jre' }\n")
            result = subprocess.run([sys.executable, "-I", RESOLVER, root, out],
                                    capture_output=True, text=True, cwd=root, check=True)
            poms = json.loads(result.stdout)["poms"]
            self.assertEqual([p["buildFile"] for p in poms], ["build.gradle"])


class PomParsingTests(unittest.TestCase):
    def test_parse_pom_reads_parent_properties_and_management(self):
        parent, props, managed = gr.parse_pom(make_pom(
            "g", "a", "1.0", parent=("g", "parent", "0.9"), properties={"x.version": "3.3"},
            managed=[("g", "lib", "${x.version}", "", ""), ("g", "bom", "2.0", "import", "pom")]))
        self.assertEqual(parent, ("g", "parent", "0.9"))
        self.assertEqual(props["x.version"], "3.3")
        self.assertIn(("g", "lib", "${x.version}", "", ""), managed)
        self.assertIn(("g", "bom", "2.0", "import", "pom"), managed)

    def test_parse_pom_refuses_doctype_entities(self):
        self.assertIsNone(gr.parse_pom('<!DOCTYPE project [ <!ENTITY x "y"> ]><project/>'))


class SafetyTests(unittest.TestCase):
    def test_coordinate_with_xml_metacharacters_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", """
                dependencies {
                    implementation 'g:a</version><!--:1.0'
                    implementation 'ok:lib:1.0'
                }
            """)
            self.assertEqual(resolve(root, "build.gradle"), [("ok", "lib", "1.0")])

    def test_malformed_build_file_does_not_raise(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "build.gradle", "dependencies { implementation 'g:a:1.0'\n/* unterminated")
            resolve(root, "build.gradle")


if __name__ == "__main__":
    unittest.main(verbosity=2)
