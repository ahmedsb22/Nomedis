package com.nomadix.traveler.client;

import com.nomadix.traveler.dto.FlightDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * Client Feign declaratif vers FLIGHT-SERVICE.
 * name = nom Eureka (pas d'URL en dur) : Feign resout via Eureka
 * et repartit entre les instances grace au LoadBalancer.
 */
@FeignClient(name = "FLIGHT-SERVICE")
public interface FlightClient {

    @GetMapping("/api/flights/{id}")
    FlightDTO getFlightById(@PathVariable("id") Long id);

    @GetMapping("/api/flights")
    List<FlightDTO> getAllFlights();
}