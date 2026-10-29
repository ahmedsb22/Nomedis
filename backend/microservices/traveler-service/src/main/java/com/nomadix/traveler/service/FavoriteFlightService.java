package com.nomadix.traveler.service;

import com.nomadix.traveler.client.FlightClient;
import com.nomadix.traveler.dto.FlightDTO;
import com.nomadix.traveler.repository.TravelerRepository;
import com.nomadix.traveler.security.BearerTokens;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Scenario 2 de l'atelier : favoris.
 * On ne stocke que les IDs (database-per-service respectee),
 * l'enrichissement se fait par OpenFeign vers FLIGHT-SERVICE.
 */
@Service
public class FavoriteFlightService {

    private final TravelerRepository travelerRepository;
    private final FlightClient flightClient;

    public FavoriteFlightService(TravelerRepository travelerRepository, FlightClient flightClient) {
        this.travelerRepository = travelerRepository;
        this.flightClient = flightClient;
    }

    public List<FlightDTO> getFavoriteFlights(Long travelerId) {
        var traveler = travelerRepository.findById(travelerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Voyageur introuvable : " + travelerId));
        // Jeton capture sur le thread de la requete puis transmis explicitement a Feign.
        String authorization = BearerTokens.current();
        // Le fallback renvoie null pour un vol indisponible : on filtre pour que la
        // reponse respecte le schema documente (tableau de FlightDTO, jamais [null]).
        return traveler.getFavoriteFlights().stream()
                .map(flightId -> flightClient.getFlightById(flightId, authorization))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void saveFavoriteFlight(Long travelerId, Long flightId) {
        // Validation synchrone : le vol existe-t-il ? (appel Feign bloquant = PRINCIPE DU COURS)
        FlightDTO flight = flightClient.getFlightById(flightId, BearerTokens.current());
        if (flight == null || flight.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vol introuvable : " + flightId);
        }
        var traveler = travelerRepository.findById(travelerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Voyageur introuvable : " + travelerId));
        traveler.getFavoriteFlights().add(flightId);       // Set = pas de doublons
        travelerRepository.save(traveler);                 // -> l'aspect AOP reprofila aussi ! (bonus demo)
    }

    public boolean removeFavoriteFlight(Long travelerId, Long flightId) {
        var traveler = travelerRepository.findById(travelerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Voyageur introuvable : " + travelerId));
        boolean removed = traveler.getFavoriteFlights().remove(flightId);
        if (removed) {
            travelerRepository.save(traveler);
        }
        return removed;
    }
}