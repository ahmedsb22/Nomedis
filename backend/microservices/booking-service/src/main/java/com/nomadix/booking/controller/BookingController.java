package com.nomadix.booking.controller;

import com.nomadix.booking.entity.Booking;
import com.nomadix.booking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cycle de vie des reservations.
 *
 * <p>La creation est une mini-saga : validation du voyageur, controle du stock,
 * decrement atomique, ecriture locale, compensation en cas d'echec.</p>
 */
@RestController
@RequestMapping("/api/bookings")
@Tag(name = "Bookings (cycle de vie)", description = "Creation, changement de statut et annulation d'une reservation")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Operation(
            summary = "Creer une reservation",
            description = """
                    Orchestration synchrone (OpenFeign) :
                    1. le voyageur est valide aupres du Traveler-Service ;
                    2. le vol et son stock sont lus sur le Flight-Service ;
                    3. une place est decrementee de facon atomique ;
                    4. la reservation est enregistree localement.

                    Le prix total est celui du vol, recupere par Feign (preuve de
                    l'appel inter-services). Jeton Keycloak obligatoire : il est
                    relaye aux deux services appeles.
                    """)
    @ApiResponse(responseCode = "201", description = "Reservation creee et place decrementee")
    @ApiResponse(responseCode = "400", description = "Parametre manquant ou invalide")
    @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide")
    @ApiResponse(responseCode = "404", description = "Voyageur ou vol inexistant")
    @ApiResponse(responseCode = "409", description = "Vol complet (aucune place disponible)")
    @ApiResponse(responseCode = "503", description = "Traveler/Flight indisponible (circuit breaker)")
    @PostMapping
    public ResponseEntity<Booking> createBooking(
            @Parameter(description = "Identifiant du voyageur (Traveler-Service)", example = "1")
            @RequestParam Long travelerId,
            @Parameter(description = "Identifiant du vol (Flight-Service)", example = "1")
            @RequestParam Long flightId) {
        Booking booking = bookingService.createBooking(travelerId, flightId);
        return ResponseEntity.status(HttpStatus.CREATED).body(booking);
    }

    @Operation(
            summary = "Changer le statut d'une reservation",
            description = """
                    Statuts acceptes : PENDING, CONFIRMED, CANCELLED.
                    - passage a CANCELLED : la place est restituee au vol ;
                    - reactivation d'une reservation annulee : une place est reprise.
                    """)
    @ApiResponse(responseCode = "200", description = "Statut mis a jour (ou inchange si deja identique)")
    @ApiResponse(responseCode = "400", description = "Statut inconnu (valeurs acceptees : PENDING, CONFIRMED, CANCELLED)")
    @ApiResponse(responseCode = "404", description = "Reservation inexistante")
    @ApiResponse(responseCode = "409", description = "Reactivation impossible : le vol n'a plus de place")
    @ApiResponse(responseCode = "503", description = "Flight-Service indisponible (circuit breaker OPEN)")
    @PutMapping("/{id}")
    public ResponseEntity<Booking> updateStatus(
            @Parameter(description = "Identifiant de la reservation", example = "1") @PathVariable Long id,
            @Parameter(description = "PENDING | CONFIRMED | CANCELLED", example = "CONFIRMED")
            @RequestParam String status) {
        return ResponseEntity.ok(bookingService.updateStatus(id, status));
    }

    @Operation(
            summary = "Annuler une reservation",
            description = "Annulation idempotente : une deuxieme annulation ne restitue pas une seconde place.")
    @ApiResponse(responseCode = "204", description = "Reservation annulee")
    @ApiResponse(responseCode = "404", description = "Reservation inexistante")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelBooking(
            @Parameter(description = "Identifiant de la reservation", example = "1") @PathVariable Long id) {
        bookingService.cancelBooking(id);
        return ResponseEntity.noContent().build();
    }
}
