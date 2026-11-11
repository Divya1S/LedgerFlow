package com.ledgerflow.transactionquery.api;

import java.time.ZoneId;

import com.ledgerflow.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Parses the time zone a client asks for. Only named regions
 * ("Europe/Paris", "UTC") are accepted: the name is also handed to
 * PostgreSQL, which reads raw offsets like "+05:30" with the sign flipped.
 */
final class Zones {

    private Zones() {
    }

    static ZoneId parse(String zone) {
        if (zone == null || !ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ZONE",
                    "zone must be a region name such as UTC or Europe/Paris");
        }
        return ZoneId.of(zone);
    }
}
