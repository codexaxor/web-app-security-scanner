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
