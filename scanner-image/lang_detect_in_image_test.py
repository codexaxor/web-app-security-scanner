#!/usr/bin/env python3
"""In-image tests for the static language detector (M8.5, D-081, review N-5).

Runs INSIDE the built secscan-scanner image so the detector's filesystem semantics — os.walk(followlinks=False),
os.path.islink, os.path.realpath confinement — are proven against the REAL container runtime (busybox base,
container Python), not just the host. Imports the SHIPPED module at /usr/local/bin/lang_detect.py (the exact file
run by run-scan.sh), not a mounted copy.

Covers the three cases the review called out: symlink-out is not followed, vendored dirs are skipped, an empty
repo is empty. Stdlib unittest only; no network, no build execution, file contents are never read.

Invoked as `python3 -I /host/lang_detect_in_image_test.py` (isolated mode drops the script dir from sys.path, so
the shipped module path is added explicitly below)."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, "/usr/local/bin")  # import the SHIPPED detector, not any mounted copy
import lang_detect  # noqa: E402


def _write(root, rel, size):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("x" * size)
    return path


class LangDetectInImageTest(unittest.TestCase):

    def test_vendored_dirs_are_skipped(self):
        with tempfile.TemporaryDirectory() as root:
            _write(root, "app/Main.java", 2000)
            _write(root, "node_modules/huge.js", 500000)
            _write(root, "build/gen.js", 500000)
            _write(root, "vendor/dep.go", 500000)
            self.assertEqual(lang_detect.detect(root), ["Java"])

    def test_symlink_out_is_not_followed(self):
        with tempfile.TemporaryDirectory() as outside, tempfile.TemporaryDirectory() as root:
            target = _write(outside, "evil.java", 100000)
            _write(root, "app/Real.cs", 2000)
            os.symlink(target, os.path.join(root, "app", "link.java"))  # symlinked file out
            os.symlink(outside, os.path.join(root, "linkdir"))          # symlinked dir out
            self.assertEqual(lang_detect.detect(root), ["C#"])          # the symlinked-out Java never counts

    def test_empty_repo_is_empty(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(lang_detect.detect(root), [])


if __name__ == "__main__":
    unittest.main()
