---
description: Build the architect context bundle for the claude.ai project (read-only)
argument-hint: (none)
---

Build the architect handover bundle. This command is **read-only**:
- Do not change code, docs, migrations, config, branches or git history.
- Do not recreate containers.
- Run only read-only queries against the database.
- Never read or print `.env` values. You may read `.env.example`.

**Output folder:** `$HOME/Projects/secscan-handover/<YYYYMMDD-HHMM>-<short-sha>/`. It sits next to the repo (`$HOME/Projects/web-app-security-scanner`), not inside it. Use Asia/Dhaka time.

**General rules:**
- Facts come from the repo and from commands, not from memory.
- Write "unknown" when you cannot determine something.
- Mark inferences with "(inferred)".
- Use paths relative to the repo root.

## Files to write

**00_INDEX.md**
- generation time (Asia/Dhaka), branch, commit (short and full), `git describe --tags`
- one line per file in the bundle

**01_STATE.md**
- **Git:** all tags; local and remote branches; the last 20 commits on develop (`--oneline`); unmerged branches.
- **Build:** run `./gradlew build` and record the tests run, failed and skipped per module. Do not fix anything. If the build fails or takes more than 20 minutes, record that and continue.
- **Runtime** (if Docker is up):
  - `docker compose ps`: service, image, status, ports
  - `flyway_schema_history`: version, description, success
  - row counts per table (counts only, no data)
  - `docker images` for the app and scanner images: tag, created date, and the content-hash label if there is one
- **Configuration:**
  - every env var the app or compose reads (from `docker-compose.yml`, `.env.example`, `application*.yml`, and `@Value` / `@ConfigurationProperties`)
  - columns: **name only**, required yes/no, default, purpose
- **Versions:** Java, Gradle, Spring Boot, key libraries (from build files and lockfiles), Node and UI libraries, scanner-image tool pins (from its Dockerfile).
- **Known gaps:**
  - every item from the "Known gaps / TODOs" sections of all `docs/reports/*.md`
  - every open decision (OD-*) from `docs/DECISIONS.md`
  - each with its source file

**02_ARCHITECTURE.md**
- Package map of `core/` and `app/`: a tree with one line per package, and one line per important class (its purpose).
- `ui/` structure: pages and components, and the API calls each makes.
- **Runtime flow as numbered steps with class names:**
  1. API request
  2. scan creation and job queue (claim, lease, stale sweep, retries)
  3. worker
  4. scanner container launch
  5. output parse
  6. findings store and reconcile (transaction boundaries)
  7. issue tables
- `scanner-image/`: every file and what it does; the exit codes and their meaning.
- **Container hardening as actually implemented:** every flag, with file:line.
- **Security controls inventory**, each with file:line:
  - token encryption
  - token handling (file mount, git `extraHeader`)
  - GitLab client: TLS, host allowlist, no redirects
  - secret HMAC, key id and preview masking
  - log sanitizing
  - the workspace scoping pattern
  - auth (`SecurityConfig`)
- Two mermaid diagrams: the components, and the sequence of one scan.

**03_API.md**
- Every endpoint, taken from the controllers: method, path, auth, request parameters and body, response DTO fields, status codes and error codes.
- One example JSON per endpoint, with placeholder values only.

**04_SCHEMA.sql**
- Run `docker compose exec postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --schema-only --no-owner --no-privileges'`: schema only, **no data**.
- If the DB is not running, concatenate `db/migration/V*.sql` in version order instead, and say so at the top.

**05_MIGRATIONS.md**
- Each migration with its version and a one-line purpose.

**06_TESTS.md**
- Every test class, grouped into unit, Testcontainers IT, and real-scanner-container IT, with one line on what it proves.
- A table mapping each milestone "Done when" item in `docs/SPEC.md` to the tests that prove it.

**07_CODE_CRITICAL.md**
- The full source of the security- and domain-critical files, each under a `### path` header. Find the actual files; names may differ.
- Files to include:
  - TokenCipher
  - SecurityConfig
  - the GitLab API client
  - the token/git credential handling
  - ScannerContainerConfig
  - ScannerLauncher
  - LogSanitizer
  - ScannerEnvironmentCheck
  - the job claim/lease/sweep code
  - ScanStateService
  - ScannerOutputParser
  - ReconcileService
  - core Reconciler, Fingerprints and SecretFingerprinter
  - the repository upsert/reassignment logic (D-020/D-021)
  - `scanner-image/Dockerfile`, `run-scan.sh`, `secscan-lib.sh`
  - `docker-compose.yml`
  - the app Dockerfile
  - any other file you judge critical
- Cap the file at about 150 KB. If it would be larger, show signatures plus the key methods for the less critical files, and say which ones were trimmed.

**08_REPO_TREE.md**
- A tree of `git ls-files` (exclude `node_modules`, build output and binary assets), with one-line notes on the top-level folders.

**Copied as-is, renamed for upload:**
- `10_README.md`
- `11_CLAUDE.md`
- `12_SPEC.md`
- `13_DECISIONS.md`
- `14_WORKFLOW.md`
- `15_REVIEW_CHECKLIST.md`
- `16_MILESTONE_REPORT_TEMPLATE.md`
- `20_REPORT_<name>.md` for each file in `docs/reports/`, except the README there

## Leak check (mandatory, before finishing)

Run this from the repo root, with `OUT` set to the output folder. It never prints `.env` values:

```bash
OUT="<output folder>"
grep -rnE 'glpat-[A-Za-z0-9_-]{10,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----' "$OUT" || echo "patterns: none"
if [ -f .env ]; then
  while IFS='=' read -r k v; do
    case "$k" in ''|\#*) continue;; esac
    case "$k" in *KEY*|*SECRET*|*PASSWORD*|*TOKEN*) ;; *) continue;; esac
    v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
    [ ${#v} -ge 6 ] || continue
    grep -rqF -- "$v" "$OUT" && echo "ENV VALUE LEAK: $k"
  done < .env
  echo "env secret values: checked"
fi
```

- **If a pattern matches:** inspect it. A regex or a string literal in code is fine. Real key material or a real token is not: remove it.
- **If any "ENV VALUE LEAK" appears:** remove the occurrence and rerun the check.
- Report only key names and file names, never values.

## Final output
Print:
- the output folder
- each file with its size, and the total size
- the build summary
- the leak-check result
- every "unknown" item

Do not commit anything. The bundle lives outside the repo.
