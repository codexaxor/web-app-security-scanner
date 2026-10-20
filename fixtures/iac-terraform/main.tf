# Synthetic Terraform fixture for M6 IaC (Checkov) tests. Intentionally misconfigured so Checkov fires.
# NOT real infrastructure — no real account ids, no secrets.

resource "aws_s3_bucket" "data" {
  bucket = "secscan-fixture-bucket"
}

# Missing encryption / public-access-block / versioning → several CKV_AWS_* findings.
resource "aws_security_group" "wide_open" {
  name = "wide-open"
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"] # CKV_AWS_24: SSH open to the world
  }
}
