# M8 S10b — duplicate ignored SAST sub-issues (investigation, read-only)

**Status:** investigation only. No data changed; no fingerprint/reconcile code touched. The fix is proposed as
**OD-19** (DECISIONS.md) for a human to schedule, because it changes a domain rule (reconcile status
transitions, rule 3).

## What was observed

The dev database (`postgres-data`, workspace `…0001`) holds **4 ignored SAST sub-issues** for one rule,
`opengrep-rules.java.spring.security.audit.spring-csrf-disabled` — two files, each with **two rows at the same
file and line but different fingerprints**:

| file | line | fingerprint (12) | snippet_hash (16) | first_seen scan | last_seen scan | status | reason |
|------|------|------------------|-------------------|-----------------|----------------|--------|--------|
| `…/SecurityConfig.java` | 10 | `ec8cf83453f8` | `e8a163ffe5be473f` | 1127b3d3 (10-20 07:40) | 1f8a62bf (10-20 08:55) | ignored | DUPLICATE_RULE |
| `…/SecurityConfig.java` | 10 | `533c803d9eaa` | `b13bd4c448466fc9` | 5f20d47e (10-20 13:13) | f3e98c37 (10-21 20:17) | ignored | DUPLICATE_RULE |
| `…/TestSecurityConfig.java` | 10 | `8819cd9794a5` | `e8a163ffe5be473f` | 1127b3d3 (10-20 07:40) | 1f8a62bf (10-20 08:55) | ignored | DUPLICATE_RULE |
| `…/TestSecurityConfig.java` | 10 | `9e962b0cc043` | `b13bd4c448466fc9` | 5f20d47e (10-20 13:13) | f3e98c37 (10-21 20:17) | ignored | DUPLICATE_RULE |

Two facts stand out:
1. **The snippet_hash, not the line, differs.** Per file the two rows share rule + file + line 10; only the
   normalized-snippet hash differs (`e8a163…` vs `b13bd4…`). The SAST fingerprint is
   `sha256(ruleId | filePath | snippetHash)` and **deliberately excludes the line number** (domain rule 2;
   `Fingerprints.sast`). So a changed snippet hash is the *only* thing that produced a new fingerprint here.
2. **It is a one-time shift, not flapping.** `e8a163…` was last seen in the 08:55 scan; `b13bd4…` first appeared
   in the 13:13 scan and has been the only hash seen since. The hash changed once, between those two scans, and
   never changed back. Both hashes appear for *both* files in their respective scan, confirming a
   pipeline-level change rather than a per-file edit.

## Root cause

The normalized-snippet hash is computed **inside the scanner container** (`scanner-image/run-scan.sh:312-316`,
D-023/S1): Opengrep's raw `extra.lines` never leave the container; only
`sha256( awk 'NF>0{$1=$1;print}'  — blank lines dropped, each line trimmed, internal whitespace collapsed )`
is written to `/out` as `extra.snippet_sha256`. The hash is therefore a pure function of **(a)** the exact text
Opengrep reports for the match, which depends on the Opengrep binary and the pinned rule bundle, and **(b)** the
normalization algorithm.

Both (a) and (b) changed on **2026-10-20**, the same day these scans were run, during the M6 `/review-pr`
iteration:
- `12dbe23` (10-20 13:00) re-pinned/hardened the Opengrep install (Cosign-verified `release-v1.31.0`,
  exec-mount carve-out).
- `05d9121` (10-20 16:53) **introduced the shell `awk` normalization line and removed the Java
  `SnippetNormalizer`**, and re-pinned the rule bundle.

The developer rebuilt the scanner image from this in-progress work between the 08:55 and 13:13 scans, so the
13:13 scan produced a different normalized snippet (hence `b13bd4…`) for the identical CSRF finding. A different
snippet hash ⇒ a different fingerprint ⇒ reconcile sees a **brand-new finding**, not the same one. (The raw
snippets are intentionally not retained — D-023 — so we cannot byte-diff the two spans post-hoc to split the
blame between (a) and (b); the net effect is the same either way.)

### Why that left two *ignored* rows instead of one

Reconcile (`core/.../reconcile/Reconciler.java`) closes a missing finding **only when its status is `open`**:

```java
if (Existing.STATUS_OPEN.equals(e.status())
        && solvableScanners.contains(e.scanner())
        && !incomingByFp.containsKey(e.fingerprint())) {
    solved.add(e.fingerprint());   // line 95-98
}
```

These findings were auto-ignored (`DUPLICATE_RULE` — they duplicate a custom Opengrep rule, D-061). When the
fingerprint flipped:
- The **old** fingerprint (`e8a163…`) was absent from the 13:13 scan, but because it was `ignored` (not `open`)
  the solve loop skipped it — **domain rule 3, "ignored stays ignored," keeps it ignored forever.** It became a
  stale orphan.
- The **new** fingerprint (`b13bd4…`) was auto-ignored again by the same duplicate-rule cause (and, for manual
  rule/path/CVE-scope holds, this is the intended D-062 behaviour: a re-fingerprinted finding for the same
  rule+file stays ignored).

So each fingerprint flip of an already-ignored finding **adds** a row and never retires the previous one.

## Can open findings duplicate the same way? No.

Empirically, in the dev DB there are **2 duplicate clusters (same rule+file+line, >1 fingerprint) and both are
`ignored`; zero `open` findings are duplicated** this way. That matches the code: when an *open* finding is
re-fingerprinted, the old fingerprint is missing from the next successful scan and is **solved** (line 95), while
the new one opens — one solved + one open, no pile-up. The accumulation is unique to findings that were
**ignored at the moment their fingerprint changed**, for any ignore cause (auto or rule/path/CVE-scope), because
the solve transition is gated on `open`.

This is a correctness/clutter issue, not a security issue: no finding is hidden (the live finding is correctly
tracked); the surplus rows are stale *ignored* duplicates that clutter the Ignored view and inflate ignored
counts.

> Note for the UI: the two rows per file share **line 10** — line number alone does **not** separate these
> specific duplicates (only the snippet_hash does). Showing `file:line` in the Ignored view (done in S10d) helps
> general row legibility but these particular dupes will still read identically; that identity is itself the
> tell.

## Proposed fix — OD-19 (do NOT implement here)

Registered in `docs/DECISIONS.md` (M8 deferred register) as **OD-19**. Summary:

1. **Retire stale ignored orphans on a successful scan.** Extend the reconcile solve step (via the single
   desired-state resolver, D-063) so that a finding **absent from a scan whose scanner succeeded** is solved even
   when its current status is `ignored`, *provided its hold is re-derivable* — an auto cause (DUPLICATE_RULE,
   DEV_DEPENDENCY, TEST_SCOPE, LOW_EPSS, …) or a rule/path/CVE-scope rule. The live (present) fingerprint is
   re-ignored by that same cause, so user intent is preserved; only the vanished fingerprint is closed. A
   manual **sub-issue-scope** ignore (a human decision on one exact fingerprint) is left out of this, to preserve
   its audit trail — or solved too, at the human's discretion. Every such change still writes a `triage_event`
   (rule 4) and flows through the resolver (rule 3 / D-063).
2. **One-off cleanup of the rows already accumulated.** A maintenance pass (not a Flyway migration — none in M8)
   that solves ignored sub-issues whose fingerprint is absent from their repository's latest successful,
   scanner-complete scan, writing a `triage_event` each.
3. **Optional, larger: reduce SAST fingerprint churn.** Consider a structural match key (rule + file + a
   stable normalized match, insensitive to Opengrep-version line-span wobble) instead of raw snippet text, so a
   tool/rule-bundle bump does not re-fingerprint unchanged code. Bigger scanner change; weigh against (1)+(2).

**Why not just "solve ignored when missing" unconditionally:** domain rule 3 keeps an ignored finding ignored
when it is *seen again* (so re-fingerprinting under a rule-scope hold does not silently un-ignore it, D-062).
The proposal only touches findings that are **genuinely absent** from a successful scan, which is already the
condition under which open findings are solved — it closes the asymmetry rather than overriding rule 3.

## Evidence / how to reproduce the queries

Against the running dev DB (`docker exec … postgres psql -U secscan -d secscan`):
- the 4 rows: `select left(fingerprint,12), file_path, line, left(snippet_hash,16), status, first_seen_scan_id,
  last_seen_scan_id from sub_issue where rule_id='opengrep-rules.java.spring.security.audit.spring-csrf-disabled'`
- duplicate clusters by status: `select status, count(*) from (select rule_id,file_path,line,status from
  sub_issue where rule_id is not null group by 1,2,3,4 having count(distinct fingerprint)>1) c group by status;`
  → `ignored | 2`, no `open`.
- the scan timeline: `select id,status,started_at,finished_at from scan where id in (…) order by finished_at;`
