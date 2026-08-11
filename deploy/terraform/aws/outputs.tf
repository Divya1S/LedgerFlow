output "eks_cluster_name" {
  value = module.eks.cluster_name
}

output "rds_endpoint" {
  value = aws_db_instance.ledgerflow.address
}

output "redis_primary_endpoint" {
  value = aws_elasticache_replication_group.ledgerflow.primary_endpoint_address
}

output "msk_bootstrap_brokers" {
  value = aws_msk_cluster.ledgerflow.bootstrap_brokers_tls
}
