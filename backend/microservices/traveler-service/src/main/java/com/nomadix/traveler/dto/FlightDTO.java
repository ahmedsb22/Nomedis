package com.nomadix.traveler.dto;

import java.math.BigDecimal;

/** Vue partielle d'un vol, renvoyee par le Flight-Service et enrichie par Feign. */
public class FlightDTO {
    private Long id;
    private String flightNumber;
    private String departureCity;
    private String arrivalCity;
    private String departureDate;
    private Integer availableSeats;
    /** Prix unitaire : preuve que le Traveler-Service lit bien les donnees du Flight-Service. */
    private BigDecimal price;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String v) { this.flightNumber = v; }
    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String v) { this.departureCity = v; }
    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String v) { this.arrivalCity = v; }
    public String getDepartureDate() { return departureDate; }
    public void setDepartureDate(String v) { this.departureDate = v; }
    public Integer getAvailableSeats() { return availableSeats; }
    public void setAvailableSeats(Integer v) { this.availableSeats = v; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal v) { this.price = v; }
}