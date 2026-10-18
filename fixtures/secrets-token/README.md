# Fixture: secrets-token

A tiny **synthetic** repository used by `ScanPipelineIT` to prove the scan engine finds a committed secret.

The only "secret" is a made-up, obviously-fake GitHub personal access token in `config/application.properties`
that matches Betterleaks' `github-pat` rule. It is not a real credential, and no company code or private data
lives here (CLAUDE.md security rules).

Why a GitHub token and not an AWS key (M4b / D-036): Betterleaks v1.7.3 reworked `aws-access-token` into a
*composite* rule (an access key ID must be paired with a validatable secret access key), so a lone fake AWS key
is reported as `generic-api-key` or not at all offline — it cannot reliably prove detection. A fake `ghp_…`
token triggers the specific, stable `github-pat` rule with no pairing/validation/entropy caveat.

The integration test turns this directory into a throwaway git repository at runtime and clones it over
`file://` into the scanner container, so the fixture itself has no `.git`.
