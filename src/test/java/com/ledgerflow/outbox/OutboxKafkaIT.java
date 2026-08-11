package com.ledgerflow.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The transactional outbox under normal operation and under Kafka failure:
 *
 *  1. a payment's event reaches the consumer and produces exactly one
 *     notification (at-least-once transport + consumer dedup)
 *  2. a manually redelivered duplicate changes nothing
 *  3. a poison message ends up on the DLT instead of blocking the partition
 *  4. with Kafka down, payments still succeed; the outbox drains when
 *     Kafka comes back
 */
@IntegrationTest
class OutboxKafkaIT {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    KafkaContainer kafka;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    ApiTestClient api;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
    }

    private record Fixture(ApiTestClient.Session payer, String walletId, String merchantAccountId) {
    }

    private Fixture paymentFixture() {
        ApiTestClient.Session payer = api.registerAndLogin();
        ApiTestClient.Session merchant = api.registerAndLogin();
        String wallet = (String) api.post(payer.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "USD", "name", "wallet")).getBody().get("id");
        String shop = (String) api.post(merchant.token(), "/api/v1/accounts",
                Map.of("type", "MERCHANT", "currency", "USD", "name", "shop")).getBody().get("id");
        api.post(payer.token(), "/api/v1/accounts/" + wallet + "/deposits",
                Map.of("amountMinorUnits", 100_000, "currency", "USD"), UUID.randomUUID().toString());
        return new Fixture(payer, wallet, shop);
    }

    private ResponseEntity<Map> pay(Fixture f, long amount) {
        return api.post(f.payer().token(), "/api/v1/payments",
                Map.of("sourceAccountId", f.walletId(), "destinationAccountId", f.merchantAccountId(),
                        "amountMinorUnits", amount, "currency", "USD"), UUID.randomUUID().toString());
    }

    private long notificationCount(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = :u AND type = 'PAYMENT_COMPLETED'")
                .param("u", userId).query(Long.class).single();
    }

    @Test
    void paymentEventFlowsThroughOutboxToExactlyOneNotification() {
        Fixture f = paymentFixture();
        assertThat(pay(f, 5_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(notificationCount(f.payer().userId())).isEqualTo(1));

        Long unpublished = jdbc.sql("""
                        SELECT count(*) FROM outbox_events o
                        WHERE o.status <> 'PUBLISHED' AND o.aggregate_type = 'payment'
                          AND o.payload->>'payerUserId' = :payer
                        """)
                .param("payer", f.payer().userId().toString())
                .query(Long.class).single();
        assertThat(unpublished).isZero();
    }

    @Test
    void duplicateDeliveryDoesNotDuplicateSideEffects() {
        Fixture f = paymentFixture();
        pay(f, 3_000);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(notificationCount(f.payer().userId())).isEqualTo(1));

        // Redeliver the exact published event (same eventId header) by hand.
        var event = jdbc.sql("""
                        SELECT id, payload::text AS payload FROM outbox_events
                        WHERE aggregate_type = 'payment' AND event_type = 'PaymentCompleted'
                          AND payload->>'payerUserId' = :payer
                        """)
                .param("payer", f.payer().userId().toString())
                .query((rs, n) -> new String[]{rs.getString("id"), rs.getString("payload")})
                .single();
        ProducerRecord<String, String> duplicate = new ProducerRecord<>("payment.events", "dup", event[1]);
        duplicate.headers().add("eventId", event[0].getBytes());
        duplicate.headers().add("eventType", "PaymentCompleted".getBytes());
        kafkaTemplate.send(duplicate);
        kafkaTemplate.flush();

        // Give the consumer time to (not) act, then assert nothing changed.
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(notificationCount(f.payer().userId())).isEqualTo(1);
    }

    @Test
    void poisonMessageLandsOnDeadLetterTopic() {
        String marker = "poison-" + UUID.randomUUID();
        kafkaTemplate.send("payment.events", marker, "this is not json and has no headers");
        kafkaTemplate.flush();

        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("group.id", "dlt-probe-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");

        try (KafkaConsumer<String, String> probe = new KafkaConsumer<>(props)) {
            probe.subscribe(List.of("payment.events.DLT"));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = probe.poll(Duration.ofMillis(500));
                boolean found = false;
                for (var record : records) {
                    if (marker.equals(record.key())) {
                        found = true;
                    }
                }
                assertThat(found).isTrue();
            });
        }
    }

    @Test
    void kafkaOutagePaymentStillSucceedsAndOutboxDrainsAfterRecovery() {
        Fixture f = paymentFixture();
        // Let the fixture's own events publish first, so the outage window
        // only contains the payment we are watching.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'")
                        .query(Long.class).single()).isZero());

        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            // PostgreSQL commits; the API answers success. Kafka being down
            // is invisible to the payer, which is the entire point of the
            // outbox pattern.
            ResponseEntity<Map> payment = pay(f, 7_000);
            assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // The publisher tries and fails: the row stays PENDING and its
            // attempt counter climbs, but nothing is lost.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var row = jdbc.sql("""
                                SELECT status, attempts FROM outbox_events
                                WHERE aggregate_type = 'payment' AND event_type = 'PaymentCompleted'
                                  AND payload->>'payerUserId' = :payer
                                """)
                        .param("payer", f.payer().userId().toString())
                        .query((rs, n) -> new Object[]{rs.getString("status"), rs.getInt("attempts")})
                        .single();
                assertThat(row[0]).isEqualTo("PENDING");
                assertThat((Integer) row[1]).isGreaterThanOrEqualTo(1);
            });
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        // Kafka is back: the outbox drains and the consumer catches up.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Long pending = jdbc.sql("""
                            SELECT count(*) FROM outbox_events
                            WHERE status <> 'PUBLISHED' AND payload->>'payerUserId' = :payer
                            """)
                    .param("payer", f.payer().userId().toString())
                    .query(Long.class).single();
            assertThat(pending).isZero();
            assertThat(notificationCount(f.payer().userId())).isEqualTo(1);
        });
    }
}
