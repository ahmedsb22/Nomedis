package com.nomadix.booking.controller;

import com.nomadix.booking.client.FlightClient;
import com.nomadix.booking.client.TravelerClient;
import com.nomadix.booking.dto.FlightDTO;
import com.nomadix.booking.dto.TravelerDTO;
import com.nomadix.booking.security.BearerTokens;
import feign.FeignException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostic des clients OpenFeign du booking-service.
 *
 * <p>OpenFeign est un mecanisme interne : il n'apparait pas naturellement
 * comme endpoint dans Swagger. Ce controleur rend les deux appels synchrones
 * observables et testables depuis l'interface, avec le meme jeton que la
 * requete courante (celui relaye ensuite aux microservices).</p>
 */
@RestController
@RequestMapping("/api/bookings/integrations/feign")
@Tag(name = "Integrations (OpenFeign)",
        description = "Diagnostic des appels synchrones vers Traveler-Service et Flight-Service")
public class FeignIntegrationController {

    private final TravelerClient travelerClient;
    private final FlightClient flightClient;

    public FeignIntegrationController(TravelerClient travelerClient, FlightClient flightClient) {
        this.travelerClient = travelerClient;
        this.flightClient = flightClient;
    }

    @Operation(
            summary = "Verifier les deux appels OpenFeign (Traveler + Flight)",
            description = """
                    Appelle `GET /api/travelers/{travelerId}` et `GET /api/flights/{flightId}`
                    via les clients Feign, en relayant le jeton Bearer de la requete.
                    Reponse 200 dans tous les cas : le detail indique pour chaque service
                    s'il est joignable (`reachable`), si la ressource existe (`found`)
                    et la donnee lue. Un `reachable: false` signifie timeout ou circuit
                    breaker OPEN (fallback), pas une erreur de cette API.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Etat des deux appels OpenFeign"),
            @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide")
    })
    @GetMapping
    public ResponseEntity<Map<String, Object>> checkFeign(
            @Parameter(description = "Identifiant du voyageur teste", example = "1")
            @RequestParam Long travelerId,
            @Parameter(description = "Identifiant du vol teste", example = "1")
            @RequestParam Long flightId) {

        String authorization = BearerTokens.current();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("travelerService", callTraveler(travelerId, authorization));
        result.put("flightService", callFlight(flightId, authorization));
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> callTraveler(Long travelerId, String authorization) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            TravelerDTO traveler = travelerClient.getTravelerById(travelerId, authorization);
            out.put("reachable", true);
            out.put("found", traveler != null);
            out.put("data", traveler);
        } catch (FeignException.NotFound e) {
            out.put("reachable", true);
            out.put("found", false);
            out.put("httpStatus", 404);
        } catch (Exception e) {
            out.put("reachable", false);
            out.put("found", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> callFlight(Long flightId, String authorization) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            FlightDTO flight = flightClient.getFlightById(flightId, authorization);
            out.put("reachable", true);
            out.put("found", flight != null);
            out.put("data", flight);
        } catch (FeignException.NotFound e) {
            out.put("reachable", true);
            out.put("found", false);
            out.put("httpStatus", 404);
        } catch (Exception e) {
            out.put("reachable", false);
            out.put("found", false);
            out.put("error", e.getMessage());
        }
        return out;
    }
}
