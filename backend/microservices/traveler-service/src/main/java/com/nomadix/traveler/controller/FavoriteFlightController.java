package com.nomadix.traveler.controller;

import com.nomadix.traveler.dto.FlightDTO;
import com.nomadix.traveler.service.FavoriteFlightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

    @Operation(summary = "Vols favoris du voyageur (enrichis via OpenFeign)",
            description = "Chaque identifiant stocke est remplace par le vol complet lu sur le Flight-Service. "
                    + "Si un vol est momentanement indisponible (fallback), il est omis de la liste.")
    @ApiResponse(responseCode = "200", description = "Tableau de vols enrichis (jamais null)")
    @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide")
    @ApiResponse(responseCode = "404", description = "Voyageur inexistant")
    @GetMapping("/{id}/favorite-flights")
    public ResponseEntity<List<FlightDTO>> getFavoriteFlights(@PathVariable Long id) {
        return ResponseEntity.ok(favoriteFlightService.getFavoriteFlights(id));
    }

    @Operation(summary = "Ajouter un vol aux favoris (validation Feign : le vol doit exister)",
            description = "Le vol est valide en synchrone avant l'ecriture : un identifiant inconnu est refuse. "
                    + "Set cote entite : ajouter deux fois le meme vol est sans effet.")
    @ApiResponse(responseCode = "200", description = "Vol ajoute (ou deja present)")
    @ApiResponse(responseCode = "404", description = "Voyageur ou vol inexistant")
    @ApiResponse(responseCode = "503", description = "Flight-Service indisponible (circuit breaker OPEN)")
    @PostMapping("/{id}/favorite-flights/{flightId}")
    public ResponseEntity<String> saveFavoriteFlight(@PathVariable Long id, @PathVariable Long flightId) {
        favoriteFlightService.saveFavoriteFlight(id, flightId);
        return ResponseEntity.ok("Vol " + flightId + " ajoute aux favoris du voyageur " + id + ".");
    }

    @Operation(summary = "Retirer un vol des favoris")
    @ApiResponse(responseCode = "200", description = "Vol retire des favoris")
    @ApiResponse(responseCode = "404", description = "Voyageur inexistant ou vol absent des favoris")
    @DeleteMapping("/{id}/favorite-flights/{flightId}")
    public ResponseEntity<String> removeFavoriteFlight(@PathVariable Long id, @PathVariable Long flightId) {
        boolean removed = favoriteFlightService.removeFavoriteFlight(id, flightId);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok("Vol " + flightId + " retire des favoris du voyageur " + id + ".");
    }
}
