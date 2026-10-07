package com.nomadix.flight.entity;
import jakarta.persistence.*; import java.time.LocalDate;
@Entity @Table(name = "flight")
public class Flight {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String flightNumber;
    @Column(name = "departure_city", nullable = false) private String departureCity;
    @Column(name = "arrival_city", nullable = false) private String arrivalCity;
    @Column(nullable = false) private LocalDate departureDate;
    @Column(nullable = false) private int availableSeats;
    public Flight() {}
    // Getters & Setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getFlightNumber() { return flightNumber; } public void setFlightNumber(String f) { this.flightNumber = f; }
    public String getDepartureCity() { return departureCity; } public void setDepartureCity(String d) { this.departureCity = d; }
    public String getArrivalCity() { return arrivalCity; } public void setArrivalCity(String a) { this.arrivalCity = a; }
    public LocalDate getDepartureDate() { return departureDate; } public void setDepartureDate(LocalDate d) { this.departureDate = d; }
    public int getAvailableSeats() { return availableSeats; } public void setAvailableSeats(int s) { this.availableSeats = s; }
}