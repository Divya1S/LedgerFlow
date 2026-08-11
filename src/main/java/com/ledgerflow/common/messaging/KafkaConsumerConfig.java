package com.ledgerflow.common.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer failure policy: 3 retries 1 second apart, then the record goes
 * to <topic>.DLT with the failure reason in headers. A poison message
 * therefore blocks its partition for at most ~3 seconds instead of
 * forever, and nothing is silently dropped: the DLT is monitored and
 * replayable by an operator once the handler bug is fixed.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception e) -> {
                    log.error("routing poison record from {}-{}@{} to DLT: {}",
                            record.topic(), record.partition(), record.offset(), e.getMessage());
                    return new TopicPartition(record.topic() + ".DLT", record.partition() % 6);
                });
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
