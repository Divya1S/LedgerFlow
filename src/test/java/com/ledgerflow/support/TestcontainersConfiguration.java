package com.ledgerflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Shared containers for integration tests. Containers are singletons per
 * JVM: Spring's context caching plus static instances keep one PostgreSQL
 * and one Kafka for the whole failsafe run instead of one per test class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:3.9.1");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return POSTGRES;
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return KAFKA;
    }

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return REDIS;
    }

    /**
     * Deterministic LLM for the AI copilot in EVERY test context. Kafka
     * consumers from all cached Spring contexts share consumer groups, so
     * the context that happens to consume a flagged payment must have an
     * LlmClient available; providing the mock here makes the AI pipeline
     * deterministic across the whole suite.
     */
    @Bean
    com.ledgerflow.fraud.ai.MockLlmClient mockLlmClient() {
        return new com.ledgerflow.fraud.ai.MockLlmClient();
    }
}
