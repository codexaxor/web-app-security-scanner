# Real Opengrep 1.31.0 skipped / partially-analyzed output (M8.15 review S3)

`opengrep-raw-skipped-1.31.0.json` is REAL Opengrep 1.31.0 output from the built `secscan-scanner:8.21.2` image,
captured 2026-11-11 with run-scan.sh's production flags (deny-list + category `--exclude-rule`s, `--taint-intrafile`,
`--timeout-threshold 3`, `--verbose`, `--disable-nosem`, `--x-ignore-semgrepignore-files`) EXCEPT `--timeout 1`
(instead of 60) so the slow file deterministically times out. The checkout was at `/work/checkout` (as in
production), user 10001-equivalent non-root (so the `chmod 000` file is unreadable). `version`, `errors[]` and
`paths.skipped` are VERBATIM; `results` was reduced to `[]` and `paths.scanned` dropped (size). Every input file
was synthetic (never committed: slow.py was ~600 KB generated, Huge.java >1 MB):

| file | content | Opengrep reports | class |
|---|---|---|---|
| `src/slow.py` | 9 000 chained formatted-SQL assignments | `errors[]` `Timeout` ×2 (+ paths.skipped analysis_failed…) | FROZEN |
| `src/Garbage.java` | punctuation garbage | `errors[]` `Syntax error` (full parse failure) | FROZEN |
| `src/Garbage.scala` | punctuation garbage | `errors[]` `Other syntax error` (full parse failure) | FROZEN |
| `src/Huge.java` | > 1 MB | `paths.skipped` `exceeded_size_limit` | FROZEN |
| `src/noperm.py` | `chmod 000` | `paths.skipped` `insufficient_permissions` | FROZEN |
| `src/partial.py` | valid code + one unparsable line | `errors[]` `["PartialParsing",[<locations>]]` (+ paths.skipped analysis_failed…) | partially parsed — NOT frozen |
| `src/ok.js` | `eval(r.query.x)` | a normal result | — |

Observed Opengrep behaviour this relies on: `paths.skipped` is an array only under `--verbose`; it lists
`analysis_failed_parser_or_internal_error` for EVERY file that has an `errors[]` entry (incl. partially parsed
ones), so that reason alone is not a full-failure signal; `PartialParsing` carries container paths + line ranges in
its type payload; `errors[].message` quotes scanned source (never carried).

`opengrep-skipped.json` is the sanitized `/out/opengrep.json` shape run-scan.sh writes for that report — exactly
`sast_skipped_files` (secscan-lib.sh) applied to the raw file (`:app:sastSkippedFilesInImageTest` asserts the
equality in the built image, so this pair cannot drift from the shipped code). `ScannerOutputParserSastSkippedTest`
parses it.
