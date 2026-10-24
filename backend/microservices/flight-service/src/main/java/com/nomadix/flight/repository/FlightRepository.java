package com.nomadix.flight.repository;

import com.nomadix.flight.entity.Flight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlightRepository extends JpaRepository<Flight, Long> {

    /**
     * Decremente le stock en une seule instruction SQL.
     * La condition "available_seats > 0" rend l'operation atomique :
     * deux reservations simultanees ne peuvent pas vendre la meme place.
     *
     * @return 1 si une place a ete prise, 0 si le vol est complet ou inexistant.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Flight f set f.availableSeats = f.availableSeats - 1 " +
           "where f.id = :id and f.availableSeats > 0")
    int decrementSeat(@Param("id") Long id);

    /**
     * Restitue une place (annulation). Sans condition : on ne peut pas
     * depasser la capacite initiale grace a la garde cote booking.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Flight f set f.availableSeats = f.availableSeats + 1 where f.id = :id")
    int incrementSeat(@Param("id") Long id);
}
