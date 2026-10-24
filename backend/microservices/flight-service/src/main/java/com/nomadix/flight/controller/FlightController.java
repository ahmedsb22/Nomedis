package com.nomadix.flight.controller;

import com.nomadix.flight.entity.Flight;
import com.nomadix.flight.repository.FlightRepository;
import com.nomadix.flight.service.FlightStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller REST du catalogue des vols (CRUD complet + Swagger).
 */
@RestController
@RequestMapping("/api/flights")
@Tag(name = "Flights", description = "Catalogue des vols Nomadix")
public class FlightController {

    private final FlightRepository flightRepository;
    private final FlightStockService flightStockService;

    public FlightController(FlightRepository flightRepository, FlightStockService flightStockService) {
        this.flightRepository = flightRepository;
        this.flightStockService = flightStockService;
    }

    @Operation(summary = "Lister tous les vols")
    @GetMapping
    public List<Flight> getAll() {
        return flightRepository.findAll();
    }

    @Operation(summary = "Obtenir un vol par son id")
    @GetMapping("/{id}")
    public ResponseEntity<Flight> getById(@PathVariable Long id) {
        return flightRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Creer un vol")
    @PostMapping
    public Flight create(@RequestBody Flight flight) {
        return flightRepository.save(flight);
    }

    @Operation(summary = "Mettre a jour un vol")
    @PutMapping("/{id}")
    public ResponseEntity<Flight> update(@PathVariable Long id, @RequestBody Flight flight) {
        // On modifie l'entite existante : un save() de l'objet recu avec un id null
        // creerait une deuxieme ligne au lieu de mettre a jour.
        return flightRepository.findById(id)
                .map(existing -> {
                    existing.setFlightNumber(flight.getFlightNumber());
                    existing.setDepartureCity(flight.getDepartureCity());
                    existing.setArrivalCity(flight.getArrivalCity());
                    existing.setDepartureDate(flight.getDepartureDate());
                    existing.setAvailableSeats(flight.getAvailableSeats());
                    existing.setPrice(flight.getPrice());
                    return ResponseEntity.ok(flightRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Supprimer un vol")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!flightRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        flightRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reserver une place (decrement atomique du stock)")
    @PostMapping("/{id}/seats/reserve")
    public ResponseEntity<Flight> reserveSeat(@PathVariable Long id) {
        return ResponseEntity.ok(flightStockService.reserveSeat(id));
    }

    @Operation(summary = "Restituer une place (annulation de reservation)")
    @PostMapping("/{id}/seats/release")
    public ResponseEntity<Flight> releaseSeat(@PathVariable Long id) {
        return ResponseEntity.ok(flightStockService.releaseSeat(id));
    }
}
