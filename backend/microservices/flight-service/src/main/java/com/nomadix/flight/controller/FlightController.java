package com.nomadix.flight.controller;

import com.nomadix.flight.entity.Flight;
import com.nomadix.flight.repository.FlightRepository;
import com.nomadix.flight.service.FlightStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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

    @Operation(summary = "Lister tous les vols", description = "Endpoint public : aucun jeton requis.")
    @SecurityRequirements
    @GetMapping
    public List<Flight> getAll() {
        return flightRepository.findAll();
    }

    @Operation(summary = "Obtenir un vol par son id", description = "Endpoint public : aucun jeton requis.")
    @SecurityRequirements
    @GetMapping("/{id}")
    public ResponseEntity<Flight> getById(@PathVariable Long id) {
        return flightRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Creer un vol", description = "Role ADMIN requis (le frontend et la Gateway appliquent la meme regle).")
    @ApiResponse(responseCode = "200", description = "Vol cree (le statut renvoye est 200, pas 201)")
    @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide")
    @ApiResponse(responseCode = "403", description = "Role ADMIN manquant")
    @PostMapping
    public Flight create(@RequestBody Flight flight) {
        return flightRepository.save(flight);
    }

    @Operation(summary = "Mettre a jour un vol", description = "Role ADMIN requis. Met a jour l'entite existante (jamais d'INSERT implicite).")
    @ApiResponse(responseCode = "200", description = "Vol mis a jour")
    @ApiResponse(responseCode = "403", description = "Role ADMIN manquant")
    @ApiResponse(responseCode = "404", description = "Vol inexistant")
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
    @ApiResponse(responseCode = "204", description = "Vol supprime")
    @ApiResponse(responseCode = "403", description = "Role ADMIN manquant")
    @ApiResponse(responseCode = "404", description = "Vol inexistant")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!flightRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        flightRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reserver une place (decrement atomique du stock)",
            description = "Authentifie (tout role). 404 si le vol n'existe pas, 409 si le vol est complet. "
                    + "Appele en interne par le Booking-Service, en direct sur le port 8082 avec le jeton du voyageur : "
                    + "via la Gateway cette route est reservee a ADMIN (regle POST /api/flights/**).")
    @ApiResponse(responseCode = "200", description = "Place decrementee, vol renvoye a jour")
    @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide")
    @ApiResponse(responseCode = "404", description = "Vol inexistant")
    @ApiResponse(responseCode = "409", description = "Vol complet : aucune place disponible")
    @PostMapping("/{id}/seats/reserve")
    public ResponseEntity<Flight> reserveSeat(@PathVariable Long id) {
        return ResponseEntity.ok(flightStockService.reserveSeat(id));
    }

    @Operation(summary = "Restituer une place (annulation de reservation)",
            description = "Authentifie (tout role). Idempotent cote Booking (une annulation ne rend la place qu'une fois). "
                    + "Appele en interne par le Booking-Service.")
    @ApiResponse(responseCode = "200", description = "Place restituee, vol renvoye a jour")
    @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide")
    @ApiResponse(responseCode = "404", description = "Vol inexistant")
    @PostMapping("/{id}/seats/release")
    public ResponseEntity<Flight> releaseSeat(@PathVariable Long id) {
        return ResponseEntity.ok(flightStockService.releaseSeat(id));
    }
}
