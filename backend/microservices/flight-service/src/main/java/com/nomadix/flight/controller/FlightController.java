package com.nomadix.flight.controller;

import com.nomadix.flight.entity.Flight;
import com.nomadix.flight.repository.FlightRepository;
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

    public FlightController(FlightRepository flightRepository) {
        this.flightRepository = flightRepository;
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
        if (!flightRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(flightRepository.save(flight));
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
}