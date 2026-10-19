#!/usr/bin/env python3
"""Unit tests for the static EOL detector (D-055). Run on the host under ./gradlew build (eolDetectTest task),
stdlib unittest only; no network, no build execution."""
import os
import tempfile
import unittest

import eol_detect


def _scan(files):
    with tempfile.TemporaryDirectory() as root:
        for rel, content in files.items():
            path = os.path.join(root, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(content)
        return eol_detect.scan(root)


def _find(dets, product):
    return [d for d in dets if d["product"] == product]


class EolDetectTest(unittest.TestCase):

    def test_maven_spring_boot_parent_and_java_version(self):
        pom = ('<project><parent><artifactId>spring-boot-starter-parent</artifactId>'
               '<version>2.1.5.RELEASE</version></parent>'
               '<properties><java.version>1.8</java.version></properties></project>')
        dets = _scan({"pom.xml": pom})
        sb = _find(dets, "spring-boot")
        self.assertEqual(len(sb), 1)
        self.assertEqual(sb[0]["cycle"], "2.1")
        self.assertEqual(sb[0]["filePath"], "pom.xml")
        java = _find(dets, "eclipse-temurin")
        self.assertEqual(java[0]["cycle"], "8")  # legacy 1.8 -> major 8

    def test_gradle_spring_boot_plugin_and_toolchain(self):
        gradle = ("plugins { id 'org.springframework.boot' version '2.7.3' }\n"
                  "java { sourceCompatibility = JavaVersion.VERSION_17 }\n")
        dets = _scan({"build.gradle": gradle})
        self.assertEqual(_find(dets, "spring-boot")[0]["cycle"], "2.7")
        self.assertEqual(_find(dets, "eclipse-temurin")[0]["cycle"], "17")

    def test_dotnet_target_framework(self):
        dets = _scan({"App.csproj": "<TargetFramework>net6.0</TargetFramework>"})
        self.assertEqual(_find(dets, "dotnet")[0]["cycle"], "6.0")

    def test_node_nvmrc_and_engines(self):
        dets = _scan({".nvmrc": "18.17.1\n",
                      "pkg/package.json": '{"engines":{"node":">=20.0.0"}}'})
        cycles = sorted(d["cycle"] for d in _find(dets, "nodejs"))
        self.assertEqual(cycles, ["18", "20"])

    def test_dockerfile_base_images(self):
        df = "FROM alpine:3.14\nFROM node:16\nFROM quay.io/keycloak/keycloak:20.0.3\nFROM scratch\n"
        dets = _scan({"Dockerfile": df})
        self.assertEqual(_find(dets, "alpine-linux")[0]["cycle"], "3.14")
        self.assertEqual(_find(dets, "nodejs")[0]["cycle"], "16")
        self.assertEqual(_find(dets, "keycloak")[0]["cycle"], "20")
        # `FROM scratch` (no tag) and unknown images produce nothing.

    def test_latest_and_untagged_images_are_ignored(self):
        dets = _scan({"Dockerfile": "FROM node:latest\nFROM alpine\n"})
        self.assertEqual(dets, [])

    def test_git_dir_is_skipped(self):
        dets = _scan({".git/pom.xml": "<parent><artifactId>spring-boot-starter-parent</artifactId>"
                                      "<version>2.1.0</version></parent>"})
        self.assertEqual(dets, [])

    def test_no_detections_is_empty_not_error(self):
        dets = _scan({"README.md": "hello"})
        self.assertEqual(dets, [])


if __name__ == "__main__":
    unittest.main()
