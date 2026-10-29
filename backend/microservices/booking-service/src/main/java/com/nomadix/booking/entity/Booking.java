package com.nomadix.booking.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Reservation d'une place de vol.
 *
 * <p>Les annotations {@code @Schema} documentent le contrat de sortie :
 * identifiants externes (travelerId, flightId), statut, prix et horodatage.
 * Aucune jointure SQL n'existe entre les services : travelerId et flightId
 * sont des references logiques.</p>
 */
@Entity
@Table(name = "bookings")
@Schema(name = "Booking", description = "Reservation orchestrée par le Booking-Service")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Identifiant de la reservation", example = "1",
            accessMode = Schema.AccessMode.READ_ONLY)
    private Long id;

    @Schema(description = "Identifiant logique du voyageur (Traveler-Service)", example = "1")
    private Long travelerId;

    @Schema(description = "Identifiant logique du vol (Flight-Service)", example = "1")
    private Long flightId;

    @Schema(description = "Cycle de vie de la reservation",
            allowableValues = {"PENDING", "CONFIRMED", "CANCELLED"},
            example = "CONFIRMED")
    private String status; // PENDING, CONFIRMED, CANCELLED

    @Schema(description = "Prix du vol recupere par OpenFeign au moment de la reservation",
            example = "350.50")
    private BigDecimal totalPrice;

    @Schema(description = "Date de creation (remplie par @PrePersist)", example = "2026-10-24T18:05:12",
            accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        // Ne pas ecraser un statut deja fixe par le service (ex: CONFIRMED).
        if (status == null) {
            status = "PENDING";
        }
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTravelerId() { return travelerId; }
    public void setTravelerId(Long travelerId) { this.travelerId = travelerId; }

    public Long getFlightId() { return flightId; }
    public void setFlightId(Long flightId) { this.flightId = flightId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getTotalPrice() { return totalPrice; }
    public void setTotalPrice(BigDecimal totalPrice) { this.totalPrice = totalPrice; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
