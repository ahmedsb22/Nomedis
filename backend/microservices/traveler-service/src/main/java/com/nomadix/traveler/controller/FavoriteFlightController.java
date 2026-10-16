package com.nomadix.traveler.controller;

import com.nomadix.traveler.dto.FlightDTO;
import com.nomadix.traveler.service.FavoriteFlightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints des vols favoris (communication synchrone OpenFeign).
 */
@RestController
@RequestMapping("/api/travelers")
@Tag(name = "Favorite Flights", description = "Favoris du voyageur (OpenFeign vers Flight-Service)")
public class FavoriteFlightController {

    private final FavoriteFlightService favoriteFlightService;

    public FavoriteFlightController(FavoriteFlightService s) { this.favoriteFlightService = s; }

    @Operation(summary = "Vols favoris du voyageur (enrichis via OpenFeign)")
    @GetMapping("/{id}/favorite-flights")
    public ResponseEntity<List<FlightDTO>> getFavoriteFlights(@PathVariable Long id) {
        return ResponseEntity.ok(favoriteFlightService.getFavoriteFlights(id));
    }

    @Operation(summary = "Ajouter un vol aux favoris (validation Feign : le vol doit exister)")
    @PostMapping("/{id}/favorite-flights/{flightId}")
    public ResponseEntity<String> saveFavoriteFlight(@PathVariable Long id, @PathVariable Long flightId) {
        favoriteFlightService.saveFavoriteFlight(id, flightId);
        return ResponseEntity.ok("Vol " + flightId + " ajoute aux favoris du voyageur " + id + ".");
    }
}