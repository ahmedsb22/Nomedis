package com.nomadix.flight.service;

import com.nomadix.flight.entity.Flight;
import com.nomadix.flight.repository.FlightRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gestion du stock de places.
 *
 * Le decrement est fait par une requete UPDATE conditionnelle
 * (available_seats > 0) dans une transaction : c'est ce qui evite
 * la surreservation quand deux reservations arrivent en parallele.
 */
@Service
public class FlightStockService {

    private final FlightRepository flightRepository;

    public FlightStockService(FlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    @Transactional
    public Flight reserveSeat(Long flightId) {
        flightRepository.findById(flightId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Vol " + flightId + " inexistant"));

        int updated = flightRepository.decrementSeat(flightId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Vol " + flightId + " complet : aucune place disponible");
        }

        return flightRepository.findById(flightId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Vol " + flightId + " inexistant"));
    }

    @Transactional
    public Flight releaseSeat(Long flightId) {
        int updated = flightRepository.incrementSeat(flightId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Vol " + flightId + " inexistant");
        }

        return flightRepository.findById(flightId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Vol " + flightId + " inexistant"));
    }
}
