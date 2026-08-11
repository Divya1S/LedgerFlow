# REFERENCE ARCHITECTURE, NOT APPLIED.
# This stack documents how LedgerFlow would run on AWS (EKS + RDS +
# ElastiCache + MSK). It is kept `terraform validate`-clean but has never
# been applied against a real account by this project (zero-cost decision;
# the real, verified deployment target is the local kind cluster, see
# deploy/kind). Costs, sizing and quotas must be reviewed before any apply.

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
}

provider "aws" {
  region = var.region
}
