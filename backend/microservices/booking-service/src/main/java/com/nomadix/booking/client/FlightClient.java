package com.nomadix.booking.client;
import com.nomadix.booking.dto.FlightDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
// Appelle le Flight-Service via Eureka (pas d'URL en dur !)
@FeignClient(name = "flight-service")
public interface FlightClient {
    @GetMapping("/api/flights/{id}")
    FlightDTO getFlightById(@PathVariable("id") Long id);
}