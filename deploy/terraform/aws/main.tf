data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs             = slice(data.aws_availability_zones.available.names, 0, 3)
  private_subnets = [for i in range(3) : cidrsubnet(var.vpc_cidr, 4, i)]
  public_subnets  = [for i in range(3) : cidrsubnet(var.vpc_cidr, 4, i + 8)]
  tags = {
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ----------------------------------------------------------------------------
# Network: 3 AZs, private subnets for everything stateful and the cluster,
# public subnets only for the load balancer and NAT.
# ----------------------------------------------------------------------------
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.16"

  name            = var.project
  cidr            = var.vpc_cidr
  azs             = local.azs
  private_subnets = local.private_subnets
  public_subnets  = local.public_subnets

  enable_nat_gateway   = true
  single_nat_gateway   = false # one per AZ: NAT is a single point of failure otherwise
  enable_dns_hostnames = true

  tags = local.tags
}

# ----------------------------------------------------------------------------
# EKS: the app is stateless, so nodes are interchangeable; system state
# lives in RDS/MSK/ElastiCache.
# ----------------------------------------------------------------------------
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.31"

  cluster_name    = var.project
  cluster_version = "1.31"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  cluster_endpoint_public_access = true

  eks_managed_node_groups = {
    apps = {
      instance_types = ["m6g.large"]
      ami_type       = "AL2023_ARM_64_STANDARD"
      min_size       = 2
      max_size       = 6
      desired_size   = 2
    }
  }

  tags = local.tags
}

# ----------------------------------------------------------------------------
# RDS PostgreSQL: the source of truth gets Multi-AZ, encrypted storage,
# backups, and deletion protection.
# ----------------------------------------------------------------------------
resource "aws_db_subnet_group" "ledgerflow" {
  name       = "${var.project}-db"
  subnet_ids = module.vpc.private_subnets
  tags       = local.tags
}

resource "aws_security_group" "postgres" {
  name   = "${var.project}-postgres"
  vpc_id = module.vpc.vpc_id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }

  tags = local.tags
}

resource "aws_db_instance" "ledgerflow" {
  identifier     = var.project
  engine         = "postgres"
  engine_version = "17"
  instance_class = var.db_instance_class

  db_name  = "ledgerflow"
  username = "ledgerflow"
  password = var.db_password

  allocated_storage     = 100
  max_allocated_storage = 1000
  storage_type          = "gp3"
  storage_encrypted     = true

  multi_az                        = true
  db_subnet_group_name            = aws_db_subnet_group.ledgerflow.name
  vpc_security_group_ids          = [aws_security_group.postgres.id]
  backup_retention_period         = 14
  deletion_protection             = true
  performance_insights_enabled    = true
  enabled_cloudwatch_logs_exports = ["postgresql"]
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${var.project}-final"

  tags = local.tags
}

# ----------------------------------------------------------------------------
# ElastiCache Redis: replicated but disposable; losing it costs cache hits
# and rate limiting, never money (fail-open design in the app).
# ----------------------------------------------------------------------------
resource "aws_elasticache_subnet_group" "ledgerflow" {
  name       = "${var.project}-redis"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_security_group" "redis" {
  name   = "${var.project}-redis"
  vpc_id = module.vpc.vpc_id

  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }

  tags = local.tags
}

resource "aws_elasticache_replication_group" "ledgerflow" {
  replication_group_id       = var.project
  description                = "LedgerFlow cache and rate limiting"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = var.redis_node_type
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  subnet_group_name          = aws_elasticache_subnet_group.ledgerflow.name
  security_group_ids         = [aws_security_group.redis.id]

  tags = local.tags
}

# ----------------------------------------------------------------------------
# MSK: 3 brokers across AZs; the outbox tolerates broker outages, so MSK
# sizing optimizes for consumer throughput, not for durability of the money
# (which never depends on Kafka).
# ----------------------------------------------------------------------------
resource "aws_security_group" "kafka" {
  name   = "${var.project}-kafka"
  vpc_id = module.vpc.vpc_id

  ingress {
    from_port       = 9092
    to_port         = 9098
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }

  tags = local.tags
}

resource "aws_msk_cluster" "ledgerflow" {
  cluster_name           = var.project
  kafka_version          = "3.7.x"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = var.kafka_instance_type
    client_subnets  = module.vpc.private_subnets
    security_groups = [aws_security_group.kafka.id]

    storage_info {
      ebs_storage_info {
        volume_size = 200
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
    }
  }

  tags = local.tags
}
