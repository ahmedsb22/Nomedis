package com.nomadix.booking.client;
import com.nomadix.booking.dto.FlightDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
// Appelle le Flight-Service via Eureka (pas d'URL en dur !)
@FeignClient(name = "flight-service", fallbackFactory = FlightClientFallbackFactory.class)
public interface FlightClient {
    @GetMapping("/api/flights/{id}")
    FlightDTO getFlightById(@PathVariable("id") Long id);

    /** Decrement atomique du stock cote Flight-Service (409 si complet). */
    @PostMapping("/api/flights/{id}/seats/reserve")
    FlightDTO reserveSeat(@PathVariable("id") Long id);

    /** Restitution d'une place lors d'une annulation (compensation). */
    @PostMapping("/api/flights/{id}/seats/release")
    FlightDTO releaseSeat(@PathVariable("id") Long id);
}
