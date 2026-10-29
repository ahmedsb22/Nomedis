package com.nomadix.flight.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Vol du catalogue.
 *
 * <p>Les annotations {@code @Schema} alimentent la specification OpenAPI :
 * sans elles, Swagger UI affiche des champs sans exemple ni contrainte et le
 * testeur doit deviner le format attendu (date, prix, nombre de places).</p>
 */
@Entity
@Table(name = "flight")
@Schema(name = "Flight", description = "Vol du catalogue Nomadix et son stock de places")
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Identifiant genere par le service (ignorer en creation)",
            example = "1", accessMode = Schema.AccessMode.READ_ONLY)
    private Long id;

    @Column(nullable = false)
    @Schema(description = "Numero de vol commercial", example = "TU700",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String flightNumber;

    @Column(name = "departure_city", nullable = false)
    @Schema(description = "Ville de depart", example = "Tunis",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String departureCity;

    @Column(name = "arrival_city", nullable = false)
    @Schema(description = "Ville d arrivee", example = "Paris",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String arrivalCity;

    @Column(nullable = false)
    @Schema(description = "Date de depart (AAAA-MM-JJ)", example = "2026-12-20",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate departureDate;

    @Column(nullable = false)
    @Schema(description = "Places encore vendables (decremente par le Booking-Service)",
            example = "120", requiredMode = Schema.RequiredMode.REQUIRED)
    private int availableSeats;

    @Column(name = "price")
    @Schema(description = "Prix unitaire en dinars (recupere par Feign cote Booking)",
            example = "350.50")
    private BigDecimal price;

    public Flight() {
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String f) { this.flightNumber = f; }

    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String d) { this.departureCity = d; }

    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String a) { this.arrivalCity = a; }

    public LocalDate getDepartureDate() { return departureDate; }
    public void setDepartureDate(LocalDate d) { this.departureDate = d; }

    public int getAvailableSeats() { return availableSeats; }
    public void setAvailableSeats(int s) { this.availableSeats = s; }

    public BigDecimal getPrice() { return price == null ? BigDecimal.ZERO : price; }
    public void setPrice(BigDecimal p) { this.price = p; }
}
