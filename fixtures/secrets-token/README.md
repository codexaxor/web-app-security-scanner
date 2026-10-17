# Fixture: secrets-aws

A tiny **synthetic** repository used by `ScanPipelineIT` to prove the scan engine finds a committed secret.

The only "secret" is a made-up, obviously-fake AWS access key in `config/application.properties` that matches
Gitleaks' `aws-access-token` rule. It is not a real credential, and no company code or private data lives here
(CLAUDE.md security rules). Note: AWS's *documented* example key (`AKIA…EXAMPLE`) is allowlisted by Gitleaks on
purpose, so it cannot be used to prove detection — hence the synthetic value. The key value is deliberately kept
out of this README so the fixture contains exactly one finding.

The integration test turns this directory into a throwaway git repository at runtime and clones it over
`file://` into the scanner container, so the fixture itself has no `.git`.
