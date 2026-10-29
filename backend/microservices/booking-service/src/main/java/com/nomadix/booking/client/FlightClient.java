package com.nomadix.booking.client;

import com.nomadix.booking.dto.FlightDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Client Feign vers le Flight-Service (resolution Eureka, pas d'URL en dur).
 *
 * Le jeton de l'utilisateur est transmis explicitement : le circuit breaker
 * execute l'appel sur un autre thread, ou le SecurityContext n'est plus visible.
 */
@FeignClient(name = "flight-service", fallbackFactory = FlightClientFallbackFactory.class)
public interface FlightClient {

    @GetMapping("/api/flights/{id}")
    FlightDTO getFlightById(@PathVariable("id") Long id,
                            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);

    /** Decrement atomique du stock cote Flight-Service (409 si complet). */
    @PostMapping("/api/flights/{id}/seats/reserve")
    FlightDTO reserveSeat(@PathVariable("id") Long id,
                          @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);

    /** Restitution d'une place lors d'une annulation (compensation). */
    @PostMapping("/api/flights/{id}/seats/release")
    FlightDTO releaseSeat(@PathVariable("id") Long id,
                          @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);
}
