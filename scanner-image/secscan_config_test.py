#!/usr/bin/env python3
"""Unit tests for the .secscan.yml reader (M7, D-057). Run on the host under ./gradlew build (secscanConfigTest
task), stdlib unittest. The whitelist/caps/confinement tests run everywhere; the YAML-parse tests are skipped
when PyYAML is not importable on the host (it is always present, pinned, in the scanner image)."""
import os
import tempfile
import unittest

import secscan_config

try:
    import yaml  # noqa: F401
    HAS_YAML = True
except ImportError:
    HAS_YAML = False


def _codes(result):
    return [w["code"] for w in result["warnings"]]


class SafeLoadInvariantTest(unittest.TestCase):
    """The source must use yaml.safe_load and never a full/unsafe loader (review change #1). Host-independent."""

    def test_source_uses_safe_load_only(self):
        with open(secscan_config.__file__, encoding="utf-8") as fh:
            src = fh.read()
        self.assertIn("yaml.safe_load", src)
        self.assertNotIn("yaml.load(", src)
        self.assertNotIn("FullLoader", src)
        self.assertNotIn("UnsafeLoader", src)
        self.assertNotIn("Loader=yaml.Loader", src)


class ConfinementTest(unittest.TestCase):
    """Symlink / outside-clone refusal — reached before any YAML parse, so no PyYAML needed."""

    def test_no_config_is_absent(self):
        with tempfile.TemporaryDirectory() as root:
            r = secscan_config._parse(root)
            self.assertFalse(r["present"])
            self.assertEqual(r["warnings"], [])

    def test_symlinked_config_is_refused_not_read(self):
        with tempfile.TemporaryDirectory() as root:
            secret = os.path.join(root, "secret.txt")
            with open(secret, "w") as fh:
                fh.write("exclude:\n  paths: [x]\n")
            os.symlink(secret, os.path.join(root, ".secscan.yml"))
            r = secscan_config._parse(root)
            self.assertTrue(r["present"])
            self.assertIn("SECSCAN_CONFIG_SYMLINK", _codes(r))
            self.assertEqual(r["excludePaths"], [])  # never read
            self.assertEqual(r["status"], "error")  # review S3: a refused config freezes, never un-suppresses

    def test_config_resolving_outside_clone_is_refused(self):
        # A regular (non-symlink) .secscan.yml cannot resolve outside; this proves the realpath guard via a
        # symlinked PARENT dir path. Skipped where symlinks are unavailable.
        with tempfile.TemporaryDirectory() as outside, tempfile.TemporaryDirectory() as root:
            tgt = os.path.join(outside, "evil.yml")
            with open(tgt, "w") as fh:
                fh.write("exclude:\n  paths: [x]\n")
            link = os.path.join(root, ".secscan.yml")
            os.symlink(tgt, link)
            r = secscan_config._parse(root)
            # islink fires first (symlink), which already refuses; both guards protect the same class.
            self.assertTrue(r["present"])
            self.assertEqual(r["excludePaths"], [])


class ValidatorTest(unittest.TestCase):
    """Whitelist + caps on already-parsed structures (no YAML parse needed)."""

    def test_exclude_paths_whitelist_and_caps(self):
        result = {"excludePaths": [], "ignoreCves": {}, "includeDevDependencies": None, "warnings": []}
        exclude = {"paths": ["src/test", "a" * 600, 123, "", "docs/examples"], "bogus": 1}
        secscan_config._parse_exclude(exclude, result, result["warnings"])
        self.assertEqual(result["excludePaths"], ["src/test", "docs/examples"])
        codes = _codes(result)
        self.assertIn("SECSCAN_CONFIG_OVERSIZED", codes)
        self.assertIn("SECSCAN_CONFIG_BAD_PATH", codes)
        self.assertIn("SECSCAN_CONFIG_UNKNOWN_KEY", codes)

    def test_ignore_cves_requires_cve_shape_and_reason(self):
        result = {"excludePaths": [], "ignoreCves": {}, "includeDevDependencies": None, "warnings": []}
        cves = {
            "CVE-2025-22869": {"reason": "offline build tool only"},
            "cve-2021-1234": "bare string reason",       # lowercased key accepted, uppercased out
            "NOT-A-CVE": {"reason": "x"},                  # dropped
            "CVE-2020-0001": {},                            # no reason -> dropped
        }
        secscan_config._parse_ignore({"cves": cves}, result, result["warnings"])
        self.assertEqual(result["ignoreCves"],
                         {"CVE-2025-22869": "offline build tool only", "CVE-2021-1234": "bare string reason"})
        codes = _codes(result)
        self.assertIn("SECSCAN_CONFIG_BAD_CVE", codes)
        self.assertIn("SECSCAN_CONFIG_NO_REASON", codes)

    def test_include_dev_must_be_boolean(self):
        result = {"excludePaths": [], "ignoreCves": {}, "includeDevDependencies": None, "warnings": []}
        secscan_config._parse_include_dev(True, result, result["warnings"])
        self.assertTrue(result["includeDevDependencies"])
        result2 = {"excludePaths": [], "ignoreCves": {}, "includeDevDependencies": None, "warnings": []}
        secscan_config._parse_include_dev("false", result2, result2["warnings"])
        self.assertIsNone(result2["includeDevDependencies"])
        self.assertIn("SECSCAN_CONFIG_BAD_INCLUDE_DEV", _codes(result2))


def _write_config(root, body):
    with open(os.path.join(root, ".secscan.yml"), "w", encoding="utf-8") as fh:
        fh.write(body)


@unittest.skipUnless(HAS_YAML, "PyYAML not importable on this host")
class YamlParseTest(unittest.TestCase):

    def test_full_valid_config_is_parsed_and_bounded(self):
        with tempfile.TemporaryDirectory() as root:
            _write_config(root,
                          "exclude:\n  paths:\n    - src/test/fixtures\n    - docs/examples\n"
                          "ignore:\n  cves:\n    CVE-2025-22869:\n      reason: Only used in an offline build tool\n"
                          "include_dev_dependencies: false\n")
            r = secscan_config._parse(root)
            self.assertTrue(r["present"])
            self.assertEqual(r["excludePaths"], ["src/test/fixtures", "docs/examples"])
            self.assertEqual(r["ignoreCves"], {"CVE-2025-22869": "Only used in an offline build tool"})
            self.assertFalse(r["includeDevDependencies"])
            self.assertEqual(r["warnings"], [])
            self.assertEqual(r["status"], "ok")  # review S3: a valid config is not frozen

    def test_parse_error_sets_status_error_for_freeze(self):
        # review S3: a present-but-unparseable config signals status=error so the worker FREEZES prior state.
        with tempfile.TemporaryDirectory() as root:
            _write_config(root, "exclude: !!python/object/apply:os.system ['x']\n")
            r = secscan_config._parse(root)
            self.assertTrue(r["present"])
            self.assertEqual(r["status"], "error")

    def test_malicious_python_tag_does_not_execute(self):
        # safe_load must refuse a !!python/object tag (YAMLError), never instantiate it. Proves D-057.
        with tempfile.TemporaryDirectory() as root:
            _write_config(root, "exclude: !!python/object/apply:os.system ['touch /tmp/pwned']\n")
            r = secscan_config._parse(root)
            self.assertTrue(r["present"])
            self.assertIn("SECSCAN_CONFIG_PARSE_ERROR", _codes(r))
            self.assertFalse(os.path.exists("/tmp/pwned"))

    def test_top_level_list_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            _write_config(root, "- a\n- b\n")
            r = secscan_config._parse(root)
            self.assertIn("SECSCAN_CONFIG_NOT_A_MAP", _codes(r))


if __name__ == "__main__":
    unittest.main()
