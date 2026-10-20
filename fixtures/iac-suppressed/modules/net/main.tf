# Nested module: an open security group fires CKV_AWS_24; the nested .checkov.yaml below tries to skip it.
# SecScan deletes every in-clone .checkov.yaml at ANY depth, so CKV_AWS_24 STILL fires here (D-037).
resource "aws_security_group" "nested_open" {
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
