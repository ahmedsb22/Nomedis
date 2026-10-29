package com.nomadix.booking.service;

import com.nomadix.booking.client.FlightClient;
import com.nomadix.booking.client.TravelerClient;
import com.nomadix.booking.dto.FlightDTO;
import com.nomadix.booking.dto.TravelerDTO;
import com.nomadix.booking.entity.Booking;
import com.nomadix.booking.messaging.BookingEventPublisher;
import com.nomadix.booking.repository.BookingRepository;
import com.nomadix.booking.security.BearerTokens;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

/**
 * Orchestration des reservations (ebauche de SAGA).
 *
 * <p>1. Valider le voyageur (Traveler-Service, synchrone)<br>
 * 2. Lire le vol et verifier le stock (Flight-Service, synchrone)<br>
 * 3. Reserver la place cote Flight (decrement atomique)<br>
 * 4. Ecrire la reservation localement<br>
 * 5. Si l'etape 4 echoue : compensation = restituer la place</p>
 *
 * <p>Le jeton Keycloak de l'utilisateur est capture une seule fois puis transmis
 * a chaque appel Feign : le circuit breaker execute ces appels sur un autre
 * thread, ou le SecurityContext n'existe plus.</p>
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final Set<String> ALLOWED_STATUS = Set.of("PENDING", "CONFIRMED", "CANCELLED");

    private final BookingRepository bookingRepository;
    private final FlightClient flightClient;
    private final TravelerClient travelerClient;
    private final BookingEventPublisher eventPublisher;

    public BookingService(BookingRepository bookingRepository,
                          FlightClient flightClient,
                          TravelerClient travelerClient,
                          BookingEventPublisher eventPublisher) {
        this.bookingRepository = bookingRepository;
        this.flightClient = flightClient;
        this.travelerClient = travelerClient;
        this.eventPublisher = eventPublisher;
    }

    public Booking createBooking(Long travelerId, Long flightId) {
        String authorization = BearerTokens.current();

        validateTraveler(travelerId, authorization);
        FlightDTO flight = checkFlightAvailability(flightId, authorization);
        reserveSeat(flightId, authorization);

        Booking booking = new Booking();
        booking.setTravelerId(travelerId);
        booking.setFlightId(flightId);
        booking.setTotalPrice(flight.getPrice());
        booking.setStatus("CONFIRMED");

        try {
            Booking saved = bookingRepository.save(booking);
            // Evenement asynchrone emis APRES l'ecriture : RabbitMQ ne doit jamais
            // annuler une reservation (publication fire-and-forget).
            eventPublisher.bookingCreated(saved);
            return saved;
        } catch (RuntimeException e) {
            // Compensation : la place avait ete decrementee, on la rend.
            releaseQuietly(flightId, authorization);
            throw e;
        }
    }

    public Booking updateStatus(Long bookingId, String newStatus) {
        if (newStatus == null || newStatus.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le statut est obligatoire");
        }
        String status = newStatus.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUS.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Statut invalide : " + newStatus + " (PENDING, CONFIRMED ou CANCELLED)");
        }

        Booking booking = findBooking(bookingId);
        String current = booking.getStatus();
        if (status.equals(current)) {
            return booking;
        }

        String authorization = BearerTokens.current();

        if ("CANCELLED".equals(status)) {
            booking.setStatus(status);
            Booking saved = bookingRepository.save(booking);
            eventPublisher.bookingCancelled(saved);
            releaseQuietly(booking.getFlightId(), authorization);
            return saved;
        }

        if ("CONFIRMED".equals(status) && "CANCELLED".equals(current)) {
            // On reactive une reservation annulee : il faut reprendre une place.
            reserveSeat(booking.getFlightId(), authorization);
        }

        booking.setStatus(status);
        return bookingRepository.save(booking);
    }

    public void cancelBooking(Long bookingId) {
        Booking booking = findBooking(bookingId);
        if ("CANCELLED".equals(booking.getStatus())) {
            return; // idempotent : ne pas restituer deux fois la place
        }
        String authorization = BearerTokens.current();
        booking.setStatus("CANCELLED");
        Booking saved = bookingRepository.save(booking);
        eventPublisher.bookingCancelled(saved);
        releaseQuietly(booking.getFlightId(), authorization);
    }

    // ---------------------------------------------------------------- privates

    private Booking findBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Reservation " + bookingId + " inexistante"));
    }

    private void validateTraveler(Long travelerId, String authorization) {
        TravelerDTO traveler;
        try {
            traveler = travelerClient.getTravelerById(travelerId, authorization);
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Voyageur " + travelerId + " inexistant");
        }
        if (traveler == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Traveler-Service indisponible : impossible de valider le voyageur");
        }
    }

    private FlightDTO checkFlightAvailability(Long flightId, String authorization) {
        FlightDTO flight;
        try {
            flight = flightClient.getFlightById(flightId, authorization);
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Vol " + flightId + " inexistant");
        }
        if (flight == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Flight-Service indisponible : timeout, panne ou circuit breaker OPEN");
        }
        if (flight.getAvailableSeats() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Vol " + flightId + " complet : aucune place disponible");
        }
        return flight;
    }

    private void reserveSeat(Long flightId, String authorization) {
        FlightDTO reserved;
        try {
            reserved = flightClient.reserveSeat(flightId, authorization);
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Vol " + flightId + " inexistant");
        } catch (FeignException.Conflict e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Vol " + flightId + " complet : aucune place disponible");
        }
        if (reserved == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Flight-Service indisponible : impossible de reserver une place");
        }
    }

    private void releaseQuietly(Long flightId, String authorization) {
        try {
            flightClient.releaseSeat(flightId, authorization);
        } catch (Exception e) {
            log.error("[COMPENSATION] place du vol {} non restituee : {}", flightId, e.toString());
        }
    }
}
