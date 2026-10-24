package com.nomadix.traveler.client;

import com.nomadix.traveler.dto.FlightDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class FlightClientFallbackFactory implements FallbackFactory<FlightClient> {
    private static final Logger log = LoggerFactory.getLogger(FlightClientFallbackFactory.class);
    @Override
    public FlightClient create(final Throwable cause) {
        log.warn("[CIRCUIT BREAKER] FLIGHT-SERVICE indisponible -> fallback favoris. Cause : {}", cause.toString());
        return new FlightClient() {
            @Override
            public FlightDTO getFlightById(Long id) {
                return null;
            }
            @Override
            public java.util.List<FlightDTO> getAllFlights() {
                return java.util.Collections.emptyList();
            }
        };
    }
}