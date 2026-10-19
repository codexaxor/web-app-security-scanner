# fixtures/iac-terraform

Synthetic, deliberately-misconfigured Terraform used by M6 IaC (Checkov) integration tests and the Slice-0
scanner-image smoke. **Not real infrastructure** — no real account ids, no secrets.

Expected Checkov findings (static `terraform` framework, no `terraform`/`helm` binary executed, D-004):
- `aws_s3_bucket.data` — missing encryption / public-access-block / versioning (`CKV_AWS_*`).
- `aws_security_group.wide_open` — SSH (22) open to `0.0.0.0/0` (`CKV_AWS_24`).

Used by `M6ScannerImageIT` (Slice 0) and `CheckovFrameworkIT.terraform` (Slice 2). The inline-skip /
in-repo-config suppression variant lives under `fixtures/iac-suppressed` (Slice 2, D-037).
