variable "region" {
  description = "AWS region"
  type        = string
  default     = "us-west-2"
}

variable "project" {
  description = "Project name used for tagging and naming"
  type        = string
  default     = "ledgerflow"
}

variable "vpc_cidr" {
  description = "VPC CIDR block"
  type        = string
  default     = "10.40.0.0/16"
}

variable "db_instance_class" {
  description = "RDS instance class"
  type        = string
  default     = "db.r6g.large"
}

variable "db_password" {
  description = "Master password for RDS (from a secret store, never tfvars in git)"
  type        = string
  sensitive   = true
}

variable "redis_node_type" {
  description = "ElastiCache node type"
  type        = string
  default     = "cache.r6g.large"
}

variable "kafka_instance_type" {
  description = "MSK broker instance type"
  type        = string
  default     = "kafka.m5.large"
}
