# Fixture: secrets-suppressed

A tiny **synthetic** repository that tries, three ways, to hide a committed secret from the scanner. SecScan
ignores all in-repo suppressors (D-037), so `BetterleaksConfigInjectionIT` proves the secret is STILL reported.

The planted secret is an obviously-fake GitHub personal access token (`ghp_…`, `github-pat` rule), committed
twice. The three in-repo suppressors — all ignored by SecScan — are:

- `.gitleaks.toml` — redefines the `github-pat` rule to a never-matching regex (defeated by the pinned
  `--config`, precedence #1).
- `config/inline.properties` — an inline `gitleaks:allow` comment (defeated by `--ignore-gitleaks-allow`).
- `.gitleaksignore` — ignores `config/application.properties`'s finding by fingerprint (defeated by pointing
  `--gitleaks-ignore-path` at a baked empty file).

No real credential or company data lives here (CLAUDE.md security rules). The test turns this directory into a
throwaway git repo at runtime; the fixture has no `.git`.
