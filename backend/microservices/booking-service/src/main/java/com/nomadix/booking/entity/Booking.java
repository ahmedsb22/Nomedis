package com.nomadix.booking.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Entity
@Table(name = "bookings")
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long travelerId;
    private Long flightId;
    private String status; // PENDING, CONFIRMED, CANCELLED
    private BigDecimal totalPrice;
    private LocalDateTime createdAt;
    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); status = "PENDING"; }
    // Getters and Setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public Long getTravelerId() { return travelerId; } public void setTravelerId(Long travelerId) { this.travelerId = travelerId; }
    public Long getFlightId() { return flightId; } public void setFlightId(Long flightId) { this.flightId = flightId; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public BigDecimal getTotalPrice() { return totalPrice; } public void setTotalPrice(BigDecimal totalPrice) { this.totalPrice = totalPrice; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}