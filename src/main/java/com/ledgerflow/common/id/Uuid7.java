package com.ledgerflow.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562): 48-bit unix-millis timestamp, 4-bit version, 12 bits of
 * randomness, variant bits, 62 bits of randomness.
 *
 * Time-ordered IDs keep B-tree inserts append-mostly (unlike UUIDv4, which
 * splatters pages) and give keyset pagination a natural tiebreaker. Generated
 * application-side so the full object graph (transaction + entries + outbox
 * event) can be built before the first INSERT.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {
    }

    public static UUID generate() {
        long now = System.currentTimeMillis();
        long randomHigh = RANDOM.nextLong();
        long randomLow = RANDOM.nextLong();

        long msb = (now << 16)                       // 48-bit timestamp
                | 0x7000L                            // version 7
                | (randomHigh & 0x0FFFL);            // 12 random bits
        long lsb = (randomLow & 0x3FFFFFFFFFFFFFFFL) // 62 random bits
                | 0x8000000000000000L;               // IETF variant
        return new UUID(msb, lsb);
    }
}
