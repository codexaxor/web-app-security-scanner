# S3 bucket: an inline #checkov:skip tries to hide a finding. SecScan recovers it from skipped_checks (D-037).
resource "aws_s3_bucket" "logs" {
  #checkov:skip=CKV_AWS_21:inline skip tries to hide versioning; SecScan recovers and promotes it
  bucket = "secscan-fixture-logs"
}

# Security group: the repo's .checkov.yaml tries to skip CKV_AWS_23. SecScan deletes the in-repo config from
# the clone, so this STILL fires.
resource "aws_security_group" "open" {
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
