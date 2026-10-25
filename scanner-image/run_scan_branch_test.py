#!/usr/bin/env python3
"""Host-run unit test for valid_branch_name in secscan-lib.sh (D-076 A1/A2).

Mirrors core.scan.BranchNameValidationTest: the shell validator must accept exactly the branch names git accepts
as a --branch shorthand (plus the A1 extras), rejecting the rest. Safety is argv-not-shell (A2), so shell
metacharacters that are structurally valid git names are ACCEPTED here and are harmless. Runs under `python3 -B`
from the scanner-image directory, like secscan_config_test.py / scope_inventory_test.py."""

import os
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
LIB = os.path.join(HERE, "secscan-lib.sh")


def valid(name):
    # Source the lib in a POSIX shell and call valid_branch_name with the candidate as a single argv element
    # (exactly how run-scan.sh passes it). Returns True when the function exits 0 (accepted).
    result = subprocess.run(
        ["sh", "-c", '. "$1"; valid_branch_name "$2"', "sh", LIB, name],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


class BranchNameShellTest(unittest.TestCase):
    ACCEPT = [
        "main", "dev", "release/1.2", "feature/x-y_z",
        "feat+x", "fix#12", "_tmp", "ünicode", "a/b/c", "v1.0.0", "fix.lockfile",
        # A2: structurally valid git names with shell metacharacters — accepted, harmless (never hit a shell).
        "$(id)", "`id`", ";rm",
    ]
    REJECT = [
        "", "-x", "-u", "a..b", "a//b", "/x", "x/", "x.lock", "a@{b", "@", "a b",
        "../x", ".hidden", "a.", "a~b", "a^b", "a:b", "a?b", "a*b", "a[b", "a\\b",
        "a\nb", "a\tb",
    ]

    def test_accepts_valid_names(self):
        for name in self.ACCEPT:
            self.assertTrue(valid(name), f"should accept {name!r}")

    def test_rejects_invalid_names(self):
        for name in self.REJECT:
            self.assertFalse(valid(name), f"should reject {name!r}")

    def test_length_boundary_is_255(self):
        self.assertTrue(valid("a" * 255))
        self.assertFalse(valid("a" * 256))


if __name__ == "__main__":
    unittest.main()
