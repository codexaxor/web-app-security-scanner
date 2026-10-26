#!/usr/bin/env python3
"""Unit tests for the static language detector (M8.5, D-081). Run on the host under ./gradlew build
(langDetectTest task), stdlib unittest only; no network, no build execution, contents never read."""
import os
import tempfile
import unittest

import lang_detect


def _write(root, rel, size):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("x" * size)
    return path


class LangDetectTest(unittest.TestCase):

    def test_detects_languages_by_size_descending(self):
        with tempfile.TemporaryDirectory() as root:
            _write(root, "src/Big.java", 10000)
            _write(root, "ui/app.ts", 3000)
            _write(root, "tiny.py", 1)  # < 1% share → dropped
            langs = lang_detect.detect(root)
            self.assertEqual(langs[0], "Java")       # largest share first
            self.assertIn("TypeScript", langs)
            self.assertNotIn("Python", langs)         # below the 1% floor

    def test_vendored_dirs_are_skipped(self):
        with tempfile.TemporaryDirectory() as root:
            _write(root, "app/Main.java", 2000)
            _write(root, "node_modules/huge.js", 500000)
            _write(root, "build/gen.js", 500000)
            _write(root, "vendor/dep.go", 500000)
            langs = lang_detect.detect(root)
            self.assertEqual(langs, ["Java"])         # vendored/build/dist content ignored

    def test_symlink_out_is_not_followed(self):
        with tempfile.TemporaryDirectory() as outside, tempfile.TemporaryDirectory() as root:
            target = _write(outside, "evil.java", 100000)
            _write(root, "app/Real.cs", 2000)
            os.symlink(target, os.path.join(root, "app", "link.java"))   # symlinked file out
            os.symlink(outside, os.path.join(root, "linkdir"))           # symlinked dir out
            langs = lang_detect.detect(root)
            self.assertEqual(langs, ["C#"])           # the symlinked-out Java is never counted

    def test_empty_repo_is_empty(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(lang_detect.detect(root), [])

    def test_dockerfile_detected(self):
        with tempfile.TemporaryDirectory() as root:
            _write(root, "docker/Dockerfile", 500)
            _write(root, "docker/Dockerfile.builder", 500)
            self.assertEqual(lang_detect.detect(root), ["Dockerfile"])

    def test_missing_dir_errors(self):
        with self.assertRaises(OSError):
            lang_detect.detect("/no/such/checkout/here")


if __name__ == "__main__":
    unittest.main()
