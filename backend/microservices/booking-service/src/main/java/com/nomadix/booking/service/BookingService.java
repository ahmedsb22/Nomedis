package com.nomadix.booking.service;
import com.nomadix.booking.client.FlightClient;
import com.nomadix.booking.dto.FlightDTO;
import com.nomadix.booking.entity.Booking;
import com.nomadix.booking.repository.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class BookingService {
    private final BookingRepository bookingRepository;
    private final FlightClient flightClient;
    public BookingService(BookingRepository bookingRepository, FlightClient flightClient) {
        this.bookingRepository = bookingRepository;
        this.flightClient = flightClient;
    }
    // Méthode avec logique de type SAGA
    @Transactional
    public Booking createBooking(Long travelerId, Long flightId) {
        // ÉTAPE 1 : Appel synchrone au Flight-Service pour vérifier la disponibilité
        FlightDTO flight = flightClient.getFlightById(flightId);
        if (flight == null || flight.getAvailableSeats() <= 0) {
            throw new RuntimeException("Échec Saga : Vol indisponible ou complet.");
        }
        // ÉTAPE 2 : Création locale de la réservation
        Booking booking = new Booking();
        booking.setTravelerId(travelerId);
        booking.setFlightId(flightId);
        booking.setTotalPrice(flight.getPrice());
        booking.setStatus("CONFIRMED"); // Simplifié pour la démo
        return bookingRepository.save(booking);
    }
}