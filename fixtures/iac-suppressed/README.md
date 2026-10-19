# fixtures/iac-suppressed
Proves D-037 for IaC. An inline `#checkov:skip=CKV_AWS_21` (recovered from skipped_checks and promoted) and a
repo `.checkov.yaml` trying to `skip-check: CKV_AWS_23` (the in-repo config is deleted from the clone, so it
still fires). Used by `CheckovConfigInjectionIT`. Not real.
