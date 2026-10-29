package com.nomadix.traveler.client;

import com.nomadix.traveler.dto.FlightDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

/**
 * Client Feign declaratif vers FLIGHT-SERVICE.
 *
 * <p>{@code name} = nom Eureka (aucune URL en dur) : Feign resout l'instance
 * via le registre et repartit la charge (Round Robin). Le jeton de l'utilisateur
 * est transmis explicitement car le circuit breaker change de thread.</p>
 */
@FeignClient(name = "FLIGHT-SERVICE", fallbackFactory = FlightClientFallbackFactory.class)
public interface FlightClient {

    @GetMapping("/api/flights/{id}")
    FlightDTO getFlightById(@PathVariable("id") Long id,
                            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);

    /** Lecture publique du catalogue : aucun jeton necessaire. */
    @GetMapping("/api/flights")
    List<FlightDTO> getAllFlights();
}
