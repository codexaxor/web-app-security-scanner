#!/usr/bin/env python3
"""SecScan in-repo config reader for .secscan.yml (M7, D-057).

Reads an OPTIONAL, owner-authored `.secscan.yml` at the checkout ROOT and emits a BOUNDED, whitelisted JSON the
worker applies at triage step 9 (exclude.paths -> EXCLUDED_PATH, ignore.cves{reason} -> IGNORE_RULE,
include_dev_dependencies -> the D-059 precedence). This is the ONLY carve-out of D-037 (in-repo suppressors
ignored), and only because every suppression it yields is surfaced + audited by the worker — never a silent drop.

Hardening (review change #1 — the M6 symlink->token-leak class):
  - Run as `python3 -I secscan_config.py <checkout>` so the scanned tree and CWD are off sys.path (a planted
    module can never be imported). Reads files as DATA ONLY; no build execution, no import/exec of repo content.
  - YAML is parsed with yaml.safe_load ONLY (never the unsafe full loader): safe_load constructs only plain
    scalars/lists/dicts, so a malicious !!python/object tag cannot instantiate anything.
  - Symlink-safe + realpath-confined: a symlinked .secscan.yml, or one whose realpath resolves OUTSIDE the clone,
    is refused with a warning (never read). The raw file bytes are NEVER echoed to /out.
  - Whitelist + caps: only exclude.paths, ignore.cves{reason}, include_dev_dependencies leave this script, with
    caps on count and string length; unknown or oversized keys are dropped with a warning.
  - No network.

Output (stdout): {"present": bool, "excludePaths": [str], "ignoreCves": {CVE: reason}, "includeDevDependencies":
bool|null, "warnings": [{code, message, filePath}], "status": "ok"|"error"}.
"""
import json
import os
import re
import sys

# NOTE: PyYAML (py3-yaml, pinned in the image, D-057) is imported LAZILY inside _parse, only when a config file
# actually exists, and ONLY ever used as yaml.safe_load (never the unsafe full loader). The lazy import lets
# the whitelist/caps/confinement logic be unit-tested on a host without PyYAML (the YAML-parse path is skipped
# there); in the scanner image py3-yaml is always present.

CONFIG_NAMES = (".secscan.yml", ".secscan.yaml")

# Bounds on what may leave the container (never trust repo-authored sizes).
MAX_PATHS = 200
MAX_CVES = 500
MAX_PATH_LEN = 512
MAX_CVE_LEN = 64
MAX_REASON_LEN = 1024

CVE_RE = re.compile(r"^CVE-\d{4}-\d{4,}$", re.IGNORECASE)


def _warn(warnings, code, message):
    warnings.append({"code": code, "message": message, "filePath": ".secscan.yml"})


def _clean_str(value):
    """A repo string is kept only if it is a str with no NUL and within length (checked per field)."""
    if not isinstance(value, str):
        return None
    if "\x00" in value:
        return None
    return value.strip()


def _parse(root):
    result = {
        "present": False,
        "excludePaths": [],
        "ignoreCves": {},
        "includeDevDependencies": None,
        "warnings": [],
        "status": "ok",
    }
    warnings = result["warnings"]

    root_real = os.path.realpath(root)
    config_path = None
    for name in CONFIG_NAMES:
        candidate = os.path.join(root, name)
        if os.path.lexists(candidate):
            config_path = candidate
            break
    if config_path is None:
        return result  # no config — present stays False

    result["present"] = True

    # Symlink-safe: never follow a symlinked config (it could point at a mounted token or any host file).
    # A config that is PRESENT but could not be safely read/parsed sets status="error" so the WORKER FREEZES the
    # prior config-derived triage state instead of treating the config as empty (review S3): a transient refusal
    # or typo must never silently un-suppress every .secscan.yml ignore. Absent/empty/valid stays "ok".
    if os.path.islink(config_path):
        _warn(warnings, "SECSCAN_CONFIG_SYMLINK", ".secscan.yml is a symlink and was refused (not read).")
        result["status"] = "error"
        return result
    if not os.path.isfile(config_path):
        _warn(warnings, "SECSCAN_CONFIG_NOT_A_FILE", ".secscan.yml is not a regular file and was refused.")
        result["status"] = "error"
        return result
    # realpath confinement: the resolved file must stay inside the checkout.
    real = os.path.realpath(config_path)
    if real != root_real and not real.startswith(root_real + os.sep):
        _warn(warnings, "SECSCAN_CONFIG_OUTSIDE_CLONE",
              ".secscan.yml resolves outside the clone and was refused (not read).")
        result["status"] = "error"
        return result

    import yaml  # lazy (see module note); pinned py3-yaml in the image.
    try:
        with open(config_path, "r", encoding="utf-8", errors="replace") as fh:
            # safe_load ONLY — the safe loader, never an unsafe one (D-057). safe_load builds only plain
            # scalars/lists/dicts, so a malicious !!python/object tag cannot instantiate anything.
            doc = yaml.safe_load(fh.read())
    except (OSError, yaml.YAMLError) as exc:
        # Never echo raw bytes; only the exception class name (yaml messages can quote file content).
        _warn(warnings, "SECSCAN_CONFIG_PARSE_ERROR",
              ".secscan.yml could not be parsed (%s); ignored." % type(exc).__name__)
        result["status"] = "error"
        return result

    if doc is None:
        return result  # empty file — a valid, intentionally-empty config (status stays ok)
    if not isinstance(doc, dict):
        _warn(warnings, "SECSCAN_CONFIG_NOT_A_MAP", ".secscan.yml top level is not a mapping; ignored.")
        result["status"] = "error"
        return result

    _known_top = {"exclude", "ignore", "include_dev_dependencies"}
    for key in doc:
        if key not in _known_top:
            _warn(warnings, "SECSCAN_CONFIG_UNKNOWN_KEY", "Unknown .secscan.yml key dropped: %s" % _safe_key(key))

    _parse_exclude(doc.get("exclude"), result, warnings)
    _parse_ignore(doc.get("ignore"), result, warnings)
    _parse_include_dev(doc.get("include_dev_dependencies"), result, warnings)
    return result


def _safe_key(key):
    # A dropped-key name is itself repo input; bound it so a huge/odd key cannot bloat /out.
    s = key if isinstance(key, str) else type(key).__name__
    return s[:64]


def _parse_exclude(exclude, result, warnings):
    if exclude is None:
        return
    if not isinstance(exclude, dict):
        _warn(warnings, "SECSCAN_CONFIG_BAD_EXCLUDE", "exclude: is not a mapping; ignored.")
        return
    for key in exclude:
        if key != "paths":
            _warn(warnings, "SECSCAN_CONFIG_UNKNOWN_KEY", "Unknown exclude key dropped: %s" % _safe_key(key))
    paths = exclude.get("paths")
    if paths is None:
        return
    if not isinstance(paths, list):
        _warn(warnings, "SECSCAN_CONFIG_BAD_EXCLUDE", "exclude.paths is not a list; ignored.")
        return
    out = []
    for raw in paths:
        if len(out) >= MAX_PATHS:
            _warn(warnings, "SECSCAN_CONFIG_TOO_MANY", "exclude.paths capped at %d entries." % MAX_PATHS)
            break
        p = _clean_str(raw)
        if p is None or p == "":
            _warn(warnings, "SECSCAN_CONFIG_BAD_PATH", "A non-string/empty exclude path was dropped.")
            continue
        if len(p) > MAX_PATH_LEN:
            _warn(warnings, "SECSCAN_CONFIG_OVERSIZED", "An oversized exclude path was dropped.")
            continue
        out.append(p)
    result["excludePaths"] = out


def _parse_ignore(ignore, result, warnings):
    if ignore is None:
        return
    if not isinstance(ignore, dict):
        _warn(warnings, "SECSCAN_CONFIG_BAD_IGNORE", "ignore: is not a mapping; ignored.")
        return
    for key in ignore:
        if key != "cves":
            _warn(warnings, "SECSCAN_CONFIG_UNKNOWN_KEY", "Unknown ignore key dropped: %s" % _safe_key(key))
    cves = ignore.get("cves")
    if cves is None:
        return
    if not isinstance(cves, dict):
        _warn(warnings, "SECSCAN_CONFIG_BAD_IGNORE", "ignore.cves is not a mapping; ignored.")
        return
    out = {}
    for cve, spec in cves.items():
        if len(out) >= MAX_CVES:
            _warn(warnings, "SECSCAN_CONFIG_TOO_MANY", "ignore.cves capped at %d entries." % MAX_CVES)
            break
        if not isinstance(cve, str) or len(cve) > MAX_CVE_LEN or not CVE_RE.match(cve.strip()):
            _warn(warnings, "SECSCAN_CONFIG_BAD_CVE", "A non-CVE ignore key was dropped: %s" % _safe_key(cve))
            continue
        reason = _extract_reason(spec)
        if reason is None:
            _warn(warnings, "SECSCAN_CONFIG_NO_REASON",
                  "ignore.cves.%s has no reason and was dropped (a reason is required)." % cve.strip().upper())
            continue
        out[cve.strip().upper()] = reason[:MAX_REASON_LEN]
    result["ignoreCves"] = out


def _extract_reason(spec):
    """Reason from {reason: "..."} or a bare string; required and non-empty."""
    if isinstance(spec, dict):
        reason = _clean_str(spec.get("reason"))
    else:
        reason = _clean_str(spec)
    if reason is None or reason == "":
        return None
    return reason


def _parse_include_dev(value, result, warnings):
    if value is None:
        return
    if not isinstance(value, bool):
        _warn(warnings, "SECSCAN_CONFIG_BAD_INCLUDE_DEV",
              "include_dev_dependencies is not a boolean; ignored.")
        return
    result["includeDevDependencies"] = value


def main(argv):
    if len(argv) != 2:
        print(json.dumps({"present": False, "excludePaths": [], "ignoreCves": {},
                          "includeDevDependencies": None,
                          "warnings": [{"code": "SECSCAN_CONFIG_USAGE",
                                        "message": "usage: secscan_config.py <checkout>", "filePath": ".secscan.yml"}],
                          "status": "error"}))
        return 2
    root = argv[1]
    try:
        result = _parse(root)
    except Exception as exc:  # defensive: never crash the scan on a config read
        result = {"present": False, "excludePaths": [], "ignoreCves": {}, "includeDevDependencies": None,
                  "warnings": [{"code": "SECSCAN_CONFIG_ERROR",
                                "message": "config read failed (%s)" % type(exc).__name__,
                                "filePath": ".secscan.yml"}],
                  "status": "error"}
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
