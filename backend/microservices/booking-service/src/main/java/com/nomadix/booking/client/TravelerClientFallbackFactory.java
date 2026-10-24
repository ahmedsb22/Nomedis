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
        return new TravelerClient() {
            @Override
            public TravelerDTO getTravelerById(Long id) {
                if (cause instanceof FeignException.NotFound) {
                    throw (FeignException) cause;
                }
                // null = service indisponible ; le BookingService renvoie 503.
                return null;
            }
        };
    }
}
