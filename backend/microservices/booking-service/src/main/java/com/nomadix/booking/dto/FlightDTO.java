package com.nomadix.booking.dto;
import java.math.BigDecimal;
public class FlightDTO {
    private Long id;
    private String origin;
    private String destination;
    private int availableSeats;
    private BigDecimal price;
    // Getters and Setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getOrigin() { return origin; } public void setOrigin(String origin) { this.origin = origin; }
    public String getDestination() { return destination; } public void setDestination(String destination) { this.destination = destination; }
    public int getAvailableSeats() { return availableSeats; } public void setAvailableSeats(int availableSeats) { this.availableSeats = availableSeats; }
    public BigDecimal getPrice() { return price; } public void setPrice(BigDecimal price) { this.price = price; }
}