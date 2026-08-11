package com.ledgerflow.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Topic declarations. Partitions are keyed by aggregate id, so per-aggregate
 * ordering holds within a partition. Single replica because local dev and
 * kind run one broker; production values come from configuration, not code.
 */
@Configuration
@EnableScheduling
public class KafkaTopicsConfig {

    @Bean
    KafkaAdmin.NewTopics ledgerflowTopics() {
        return new KafkaAdmin.NewTopics(
                topic("ledger.events"),
                topic("payment.events"),
                topic("notification.events"),
                topic("fraud.events"),
                topic("payment.events.DLT"),
                topic("ledger.events.DLT"));
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(6).replicas(1).build();
    }
}
