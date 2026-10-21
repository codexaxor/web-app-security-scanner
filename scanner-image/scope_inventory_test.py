#!/usr/bin/env python3
"""Unit tests for the declared dev/test scope inventory (D-059). Host-run (scopeInventoryTest), stdlib only."""
import os
import tempfile
import unittest

import scope_inventory


def _scan(files):
    with tempfile.TemporaryDirectory() as root:
        for rel, content in files.items():
            path = os.path.join(root, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(content)
        return scope_inventory.scan(root)["scopes"]


def _coords(scopes, scope=None):
    return {s["coordinate"] for s in scopes if scope is None or s["scope"] == scope}


def json_dumps_lock():
    # npm v3 lockfile: mocha is a pure dev transitive; debug appears both as a dev path (under mocha) AND as a
    # prod path (under express), so it must stay visible; express is prod.
    return """{
      "lockfileVersion": 3,
      "packages": {
        "": {"name": "app"},
        "node_modules/express": {"version": "4.0.0"},
        "node_modules/mocha": {"version": "10.0.0", "dev": true},
        "node_modules/mocha/node_modules/debug": {"version": "4.0.0", "dev": true},
        "node_modules/express/node_modules/debug": {"version": "4.0.0"}
      }
    }"""


class ScopeInventoryTest(unittest.TestCase):

    def test_maven_test_scope_is_marked_prod_is_not(self):
        pom = """<project><dependencies>
          <dependency><groupId>junit</groupId><artifactId>junit</artifactId><scope>test</scope></dependency>
          <dependency><groupId>org.apache.commons</groupId><artifactId>commons-text</artifactId></dependency>
        </dependencies></project>"""
        scopes = _scan({"pom.xml": pom})
        self.assertEqual(_coords(scopes, "test"), {"junit:junit"})
        self.assertNotIn("org.apache.commons:commons-text", _coords(scopes))

    def test_npm_dev_dependencies_marked_unless_also_prod(self):
        pkg = """{"dependencies": {"lodash": "4.0.0"},
                  "devDependencies": {"mocha": "10.0.0", "lodash": "4.0.0"}}"""
        scopes = _scan({"package.json": pkg})
        self.assertEqual(_coords(scopes, "dev"), {"mocha"})  # lodash is also prod -> not marked

    def test_gradle_test_configs_marked_prod_not(self):
        build = """dependencies {
          testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
          implementation("com.google.guava:guava:33.0.0-jre")
          testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.10.0'
        }"""
        scopes = _scan({"build.gradle": build})
        self.assertEqual(_coords(scopes, "test"),
                         {"org.junit.jupiter:junit-jupiter", "org.junit.platform:junit-platform-launcher"})
        self.assertNotIn("com.google.guava:guava", _coords(scopes))

    def test_gradle_coordinate_in_both_test_and_prod_is_not_marked(self):
        build = """dependencies {
          implementation 'com.example:shared:1.0'
          testImplementation 'com.example:shared:1.0'
        }"""
        scopes = _scan({"build.gradle": build})
        self.assertEqual(_coords(scopes), set())

    def test_multi_module_prod_in_one_module_suppresses_test_in_another(self):
        # review S4: com.x:lib is test-scoped in module A but a compile dependency in module B. Because it is
        # production SOMEWHERE in the repo, it is NOT marked dev/test anywhere -> B's (and A's) finding stays open.
        pom_a = ("<project><dependencies><dependency><groupId>com.x</groupId>"
                 "<artifactId>lib</artifactId><scope>test</scope></dependency></dependencies></project>")
        pom_b = ("<project><dependencies><dependency><groupId>com.x</groupId>"
                 "<artifactId>lib</artifactId></dependency></dependencies></project>")
        scopes = _scan({"moduleA/pom.xml": pom_a, "moduleB/pom.xml": pom_b})
        self.assertEqual(_coords(scopes, "test"), set())

    def test_test_scope_emitted_per_manifest_when_not_prod_anywhere(self):
        # review S4: the emitted entry carries ITS OWN manifest path (keyed per manifest, not a flat map).
        pom_a = ("<project><dependencies><dependency><groupId>com.x</groupId>"
                 "<artifactId>lib</artifactId><scope>test</scope></dependency></dependencies></project>")
        pom_b = ("<project><dependencies><dependency><groupId>com.y</groupId>"
                 "<artifactId>other</artifactId></dependency></dependencies></project>")
        scopes = _scan({"moduleA/pom.xml": pom_a, "moduleB/pom.xml": pom_b})
        self.assertEqual([(s["coordinate"], s["scope"], s["filePath"]) for s in scopes],
                         [("com.x:lib", "test", os.path.join("moduleA", "pom.xml"))])

    def test_npm_lock_dev_transitive_that_is_also_prod_is_not_marked(self):
        # review S4: a package-lock dev:true transitive (mocha) is marked, but one that is ALSO reached as a prod
        # (dev:false) transitive (debug) stays visible. Entries are attributed to the lockfile path.
        pkg = """{"dependencies": {"express": "4.0.0"}, "devDependencies": {"mocha": "10.0.0"}}"""
        lock = json_dumps_lock()
        scopes = _scan({"package.json": pkg, "package-lock.json": lock})
        self.assertEqual(_coords(scopes, "dev"), {"mocha"})
        self.assertNotIn("debug", _coords(scopes))
        self.assertNotIn("express", _coords(scopes))
        # mocha is marked both from package.json (direct devDep) and from the lockfile; the lockfile attribution
        # is what matches an OSV npm finding (reported against the lockfile).
        self.assertIn("package-lock.json",
                      {s["filePath"] for s in scopes if s["coordinate"] == "mocha"})

    def test_unparseable_pom_is_named_unreadable_and_does_not_affect_other_modules(self):
        # review SF3: a bad pom is named in unreadableManifests with a warning; module B still inventories.
        bad = "<project><dependencies><dependency><groupId>x</artifactId></project>"  # malformed XML
        pom_b = ("<project><dependencies><dependency><groupId>com.y</groupId>"
                 "<artifactId>lib</artifactId><scope>test</scope></dependency></dependencies></project>")
        with tempfile.TemporaryDirectory() as root:
            for rel, content in {"moduleA/pom.xml": bad, "moduleB/pom.xml": pom_b}.items():
                path = os.path.join(root, rel)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(content)
            result = scope_inventory.scan(root)
        self.assertIn(os.path.join("moduleA", "pom.xml"), result["unreadableManifests"])
        self.assertTrue(any(w["code"] == "SCOPE_MANIFEST_UNREADABLE"
                            and w["filePath"] == os.path.join("moduleA", "pom.xml") for w in result["warnings"]))
        self.assertEqual(_coords(result["scopes"], "test"), {"com.y:lib"})  # module B unaffected
        self.assertEqual(result["status"], "ok")  # a per-manifest failure is NOT a global failure

    def test_unparseable_package_lock_is_named_unreadable(self):
        with tempfile.TemporaryDirectory() as root:
            with open(os.path.join(root, "package.json"), "w") as fh:
                fh.write('{"devDependencies": {"mocha": "10.0.0"}}')
            with open(os.path.join(root, "package-lock.json"), "w") as fh:
                fh.write("{not json")
            result = scope_inventory.scan(root)
        self.assertIn("package-lock.json", result["unreadableManifests"])

    def test_symlinked_manifest_is_not_followed(self):
        with tempfile.TemporaryDirectory() as outside, tempfile.TemporaryDirectory() as root:
            tgt = os.path.join(outside, "pom.xml")
            with open(tgt, "w") as fh:
                fh.write("<project><dependencies><dependency><groupId>x</groupId>"
                         "<artifactId>y</artifactId><scope>test</scope></dependency></dependencies></project>")
            os.symlink(tgt, os.path.join(root, "pom.xml"))
            self.assertEqual(scope_inventory.scan(root)["scopes"], [])


if __name__ == "__main__":
    unittest.main()
