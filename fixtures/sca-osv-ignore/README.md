# Fixture: sca-osv-ignore

A tiny **synthetic** npm repository (`lodash@4.17.4`, via `package-lock.json`) with an in-repo
`osv-scanner.toml` that tries to suppress a real vulnerability (`CVE-2019-10744`). SecScan ignores in-repo
OSV-Scanner config (D-037), so `OsvConfigInjectionIT` proves the vulnerability is STILL reported.

Verified against OSV-Scanner v2.7.0: with the repo's `osv-scanner.toml` honoured, lodash shows 9 vuln groups
(CVE-2019-10744 dropped); with SecScan's pinned `--config`, it shows 10 (CVE-2019-10744 present).

Needs live network (osv.dev / deps.dev), so the IT is tagged `live-osv` like `ScaLiveScanIT`. No real credential
or company data lives here. The test turns this directory into a throwaway git repo at runtime.
