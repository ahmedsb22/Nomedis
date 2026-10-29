package com.nomadix.booking.client;

import com.nomadix.booking.dto.TravelerDTO;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class TravelerClientFallbackFactory implements FallbackFactory<TravelerClient> {

    private static final Logger log = LoggerFactory.getLogger(TravelerClientFallbackFactory.class);

    @Override
    public TravelerClient create(final Throwable cause) {
        log.warn("[CIRCUIT BREAKER] Traveler-Service indisponible -> fallback. Cause : {}", cause.toString());
        return (id, authorization) -> {
            if (cause instanceof FeignException.NotFound) {
                // 404 = voyageur inexistant, information metier a remonter.
                throw (FeignException) cause;
            }
            // null => BookingService repond 503 (service indisponible).
            return null;
        };
    }
}
