#!/usr/bin/env python3
"""SAST scorecard scorer (M8.15 S7, D-104). Scores an Opengrep JSON report (produced with the PRODUCTION config
— the six new + four existing packs, category + deny exclusions, --taint-intrafile, --timeout 60) against a
benchmark, WITHOUT ever building or running the benchmark (static only, D-004).

Two modes:

  java  <expectedresults.csv>  <opengrep.json>
      OWASP BenchmarkJava v1.2 scoring. The CSV maps each BenchmarkTestNNNNN to (real-vulnerability, CWE). A test
      is "flagged" when some finding on its file carries a CWE in that category's CWE set. Per category we report
      TP/FP/FN/TN, TPR (recall), FPR and the Youden score (TPR - FPR), plus the overall.

  js    <opengrep.json>  <label>
      No labelled ground truth (Juice Shop / NodeGoat), so we print the security findings grouped by rule id and
      file count — a hits-by-rule view the reviewer reads against known vulnerable spots.

Only stdlib. The benchmark clones are NEVER committed; this script takes their paths as arguments.
"""
import json
import os
import re
import sys
from collections import defaultdict

# Benchmark category -> the CWE numbers that count as a hit for it (primary + closely-related, mirroring the
# spirit of the official OWASP Benchmark CWE mapping so a correct finding tagged with a sibling CWE still counts).
CATEGORY_CWES = {
    "cmdi": {78, 77},
    "crypto": {327, 326},
    "hash": {328, 327},
    "ldapi": {90},
    "pathtraver": {22, 23, 36, 73},
    "securecookie": {614, 1004},
    "sqli": {89, 564},
    "trustbound": {501},
    "weakrand": {330, 338},
    "xpathi": {643, 91},
    "xss": {79, 80, 83},
}

CWE_RE = re.compile(r"CWE[-\s]?(\d+)")


def cwes_of(result):
    """All CWE integers a finding is tagged with (from extra.metadata.cwe, which may be a string or a list)."""
    out = set()
    meta = (result.get("extra") or {}).get("metadata") or {}
    raw = meta.get("cwe")
    items = raw if isinstance(raw, list) else [raw]
    for item in items:
        if item is None:
            continue
        for m in CWE_RE.findall(str(item)):
            out.add(int(m))
    return out


def file_cwes(report_path):
    """Map BenchmarkTest basename -> set of CWE ints flagged anywhere in that file."""
    with open(report_path, encoding="utf-8") as fh:
        results = json.load(fh).get("results", [])
    by_file = defaultdict(set)
    for r in results:
        path = r.get("path") or (r.get("location") or {}).get("path") or ""
        base = os.path.basename(path)
        by_file[base] |= cwes_of(r)
    return by_file


def score_java(csv_path, report_path):
    by_file = file_cwes(report_path)
    # category -> counters
    tp = defaultdict(int)
    fp = defaultdict(int)
    fn = defaultdict(int)
    tn = defaultdict(int)
    with open(csv_path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = [p.strip() for p in line.split(",")]
            if len(parts) < 4:
                continue
            name, category, real, _cwe = parts[0], parts[1], parts[2] == "true", parts[3]
            cwe_set = CATEGORY_CWES.get(category, set())
            flagged = bool(by_file.get(name + ".java", set()) & cwe_set)
            if real and flagged:
                tp[category] += 1
            elif real and not flagged:
                fn[category] += 1
            elif not real and flagged:
                fp[category] += 1
            else:
                tn[category] += 1

    cats = sorted(set(tp) | set(fp) | set(fn) | set(tn))
    print(f"{'category':<13}{'CWE':<7}{'TP':>5}{'FN':>5}{'FP':>5}{'TN':>5}{'TPR':>8}{'FPR':>8}{'score':>8}")
    tot = {"tp": 0, "fn": 0, "fp": 0, "tn": 0}
    rows = []
    for c in cats:
        a, b, d, e = tp[c], fn[c], fp[c], tn[c]
        tot["tp"] += a
        tot["fn"] += b
        tot["fp"] += d
        tot["tn"] += e
        tpr = a / (a + b) if (a + b) else 0.0
        fpr = d / (d + e) if (d + e) else 0.0
        cwe = sorted(CATEGORY_CWES.get(c, {0}))[0]
        rows.append((c, cwe, a, b, d, e, tpr, fpr, tpr - fpr))
        print(f"{c:<13}{cwe:<7}{a:>5}{b:>5}{d:>5}{e:>5}{tpr:>8.2f}{fpr:>8.2f}{tpr-fpr:>8.2f}")
    TPR = tot["tp"] / (tot["tp"] + tot["fn"]) if (tot["tp"] + tot["fn"]) else 0.0
    FPR = tot["fp"] / (tot["fp"] + tot["tn"]) if (tot["fp"] + tot["tn"]) else 0.0
    print(f"{'OVERALL':<13}{'':<7}{tot['tp']:>5}{tot['fn']:>5}{tot['fp']:>5}{tot['tn']:>5}"
          f"{TPR:>8.2f}{FPR:>8.2f}{TPR-FPR:>8.2f}")
    # machine-readable tail for diffing before/after
    print("JSON " + json.dumps({c: {"tp": tp[c], "fn": fn[c], "fp": fp[c], "tn": tn[c]} for c in cats}))


def score_js(report_path, label):
    with open(report_path, encoding="utf-8") as fh:
        results = json.load(fh).get("results", [])
    by_rule = defaultdict(set)
    for r in results:
        cid = r.get("check_id", "?")
        path = r.get("path") or (r.get("location") or {}).get("path") or "?"
        # only JS/TS-relevant packs
        if ".javascript." in cid or ".typescript." in cid or cid.startswith("opengrep-rules.custom."):
            by_rule[cid].add(os.path.basename(path))
    print(f"=== {label}: JS/TS security findings by rule (rule -> file count) ===")
    for cid in sorted(by_rule, key=lambda k: (-len(by_rule[k]), k)):
        print(f"{len(by_rule[cid]):>4}  {cid}")
    print(f"total js/ts findings rules={len(by_rule)} "
          f"files={len({f for s in by_rule.values() for f in s})}")


def main(argv):
    if len(argv) >= 4 and argv[1] == "java":
        score_java(argv[2], argv[3])
    elif len(argv) >= 4 and argv[1] == "js":
        score_js(argv[2], argv[3])
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
